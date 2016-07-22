package mws.rng

import akka.actor._
import mws.rng.store._
import akka.util.ByteString
import java.util.Calendar
import scala.collection.SortedMap
import java.io.File
import java.text.SimpleDateFormat
import org.iq80.leveldb._

final case class DumpData(current: Bucket, prefList: PreferenceList, collected: List[List[Data]],
                          lastKey: Option[Key], client: Option[ActorRef])

object DumpWorker {
    def props(buckets: SortedMap[Bucket, PreferenceList], local: Node): Props = Props(new DumpWorker(buckets, local))
}
class DumpWorker(buckets: SortedMap[Bucket, PreferenceList], local: Node) extends FSM[FsmState, DumpData] with ActorLogging {
    val leveldbFactory = HashRing(context.system).leveldbFactory
    val timestamp = new SimpleDateFormat("yyyy.MM.dd-HH.mm.ss").format(Calendar.getInstance().getTime())
    val filePath = s"rng_dump_$timestamp"
    val db = leveldbFactory.open(new File(filePath), new Options().createIfMissing(true))
    val dumpStore = context.actorOf(Props(classOf[WriteStore], db))
    val stores = SelectionMemorize(context.system)
    val maxBucket = context.system.settings.config.getInt("ring.buckets")
    startWith(ReadyCollect, DumpData(0, Set.empty[Node], Nil, None, None))

    when(ReadyCollect){
        case Event(Dump, state ) =>
            buckets(state.current).foreach{n => stores.get(n, "ring_readonly_store").fold(_ ! BucketGet(state.current), _ ! BucketGet(state.current))}
            goto(Collecting) using DumpData(state.current, buckets(state.current), Nil, None, Some(sender))
    }

    when(Collecting){
        case Event(GetBucketResp(b,data), state) => // TODO add timeout if any node is not responding.
            val pList = state.prefList - (if(sender().path.address.hasLocalScope) local else sender().path.address)
            if (pList.isEmpty) {
                val lastKey = mergeBucketData((data :: state.collected).foldLeft(List.empty[Data])((acc, l) => l ::: acc), Nil) match {
                  case Nil => state.lastKey
                  case listData =>  linkKeysInDb(listData, state.lastKey)
                }
                b+1 match {
                    case `maxBucket` =>
                        stores.get(self.path.address, "ring_hash").fold(_ ! RestoreState, _ ! RestoreState)
                        log.info(s"Dump complete, sending path to hash, lastKey = $lastKey")
                        dumpStore ! PutSavingEntity("head_of_keys", (ByteString("dummy"), lastKey))
                        dumpStore ! PoisonPill //TODO stop after processing last msg
                        import mws.rng.arch.Archiver._
                        zip(filePath)
                        state.client.map(_ ! s"$filePath.zip")
                        stop()
                    case nextBucket =>
                        buckets(nextBucket).foreach{n => stores.get(n, "ring_readonly_store").fold(_ ! BucketGet(nextBucket), _ ! BucketGet(nextBucket))}
                        stay() using(DumpData(nextBucket, buckets(nextBucket), Nil, lastKey, state.client))
                }
            }
            else
                stay() using DumpData(state.current, pList, data :: state.collected, state.lastKey, state.client)

    }

    def linkKeysInDb(ldata: List[Data], prevKey: Option[Key]): Option[Key] = ldata match {
        case Nil => prevKey
        case h::t =>
            log.debug(s"dump key=${h.key}")
            dumpStore ! PutSavingEntity(h.key, (h.value, prevKey))
            linkKeysInDb(t,Some(h.key))
    }

    initialize()
}

object LoadDumpWorker {
    def props(path: String): Props = Props(new LoadDumpWorker(path))
}
class LoadDumpWorker(path: String) extends FSM[FsmState, Option[ActorRef]] with ActorLogging {
    val leveldbFactory = HashRing(context.system).leveldbFactory
    import mws.rng.arch.Archiver._
    val extraxtedDir = path.dropRight(".zip".length)
    unZipIt(path, extraxtedDir)
    val dumpDb = leveldbFactory.open(new File(extraxtedDir), new Options().createIfMissing(false))
    val store = context.actorOf(Props(classOf[ReadonlyStore], dumpDb))
    val stores = SelectionMemorize(context.system)
    startWith(ReadyCollect, None)

    when(ReadyCollect){
        case Event(LoadDump(_),_) =>
            store ! GetSavingEntity("head_of_keys")
            goto(Collecting) using Some(sender)
    }

    when(Collecting){
        case Event(SavingEntity(k,v,nextKey),state) =>
            log.debug(s"saving state $k -> $v, nextKey = $nextKey")
            stores.get(self.path.address, "ring_hash").fold(_ ! InternalPut(k,v), _ ! InternalPut(k,v))
            nextKey match {
                case None =>
                    stores.get(self.path.address, "ring_hash").fold(_ ! RestoreState, _ ! RestoreState)
                    dumpDb.close()
                    log.info("load is completed")
                    state.map(_ ! "done")
                    stop()
                case Some(key) =>
                    store ! GetSavingEntity(key)
                    stay() using state
            }
    }
}

object IterateDumpWorker {
    def props(path: String, foreach: (String,Array[Byte])=>Unit): Props = Props(new IterateDumpWorker(path,foreach))
}
class IterateDumpWorker(path: String, foreach: (String,Array[Byte])=>Unit) extends FSM[FsmState,Option[ActorRef]] with ActorLogging {
    val leveldbFactory = HashRing(context.system).leveldbFactory
    import mws.rng.arch.Archiver._
    val extraxtedDir = path.dropRight(".zip".length)
    unZipIt(path, extraxtedDir)
    val dumpDb = leveldbFactory.open(new File(extraxtedDir), new Options().createIfMissing(false))
    val store = context.actorOf(Props(classOf[ReadonlyStore], dumpDb))
    val stores = SelectionMemorize(context.system)
    startWith(ReadyCollect, None)

    when(ReadyCollect){
        case Event(IterateDump(_,_),_) =>
            store ! GetSavingEntity("head_of_keys")
            goto(Collecting) using Some(sender)
    }

    when(Collecting){
        case Event(SavingEntity(k,v,nextKey),state) =>
            log.debug(s"iterate key=$k")
            foreach(k,v.toArray)
            nextKey match {
                case None =>
                    dumpDb.close()
                    state.map(_ ! "done")
                    log.debug("iteration ended")
                    stop()
                case Some(key) =>
                    store ! GetSavingEntity(key)
                    stay() using state
            }
    }
}
