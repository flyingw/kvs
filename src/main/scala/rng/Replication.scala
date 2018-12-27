package mws.rng

import akka.actor.{ActorLogging, Props, ActorRef, FSM}
import akka.cluster.{Cluster, VectorClock}
import com.google.protobuf.{ByteString}
import mws.rng.data.{Data}
import mws.rng.msg_repl.{ReplBucketPut, ReplGetBucketVc, ReplBucketVc, ReplGetBucketIfNew, ReplFailed, ReplBucketUpToDate, ReplNewerBucketData}
import scala.annotation.tailrec
import scala.collection.immutable
import scala.collection.immutable.{SortedMap, HashMap}
import scala.concurrent.duration.{Duration}
import scalaz.Scalaz._

import ReplicationSupervisor.{State}

object ReplicationSupervisor {
  final case class Progress(done: Int, total: Int, step: Int)
  final case class State(buckets: SortedMap[Bucket, PreferenceList], progress: Progress)

  def props(buckets: SortedMap[Bucket, PreferenceList]): Props = {
    val len = buckets.size
    Props(new ReplicationSupervisor(State(buckets, Progress(done=0, total=len, step=len/4))))
  }
}

/** Sequentially update buckets */
class ReplicationSupervisor(initialState: State) extends FSM[FsmState, State] with ActorLogging {
  val actorMem = SelectionMemorize(context.system)
  val local: Node = Cluster(context.system).selfAddress

  startWith(ReadyCollect, initialState)

  when(ReadyCollect){
    case Event("go-repl", data) =>
      data.buckets.headOption match {
        case None =>
          log.info("replication: skipped")
          stop()
        case Some((b, prefList)) => 
          log.info("replication: started")
          getBucketVc(b)
          goto (Sent) using data
      }
  }

  // after ask for vc of bucket
  when(Sent){
    case Event(b: ReplBucketVc, data) =>
      val replica = data.buckets.head // safe
      val worker = {
        val vc = makevc(b.vc)
        context.actorOf(ReplicationWorker.props(b=replica._1, prefList=replica._2, vc))
      }
      replica._2.map(node => actorMem.get(node, "ring_readonly_store").fold(
        _.tell(ReplGetBucketIfNew(b=replica._1, b.vc), worker),
        _.tell(ReplGetBucketIfNew(b=replica._1, b.vc), worker),
      ))
      goto (Collecting) using data
  }

  when(Collecting){
    case Event(b: Bucket, data) =>
      data.buckets - b match {
        case empty if empty.isEmpty =>
          log.info("replication: finished")
          stop()
        case remaining =>
          val pr = data.progress
          if (pr.done =/= 0 && pr.done % pr.step === 0) log.info(s"replication: ${pr.done*100/pr.total}%")
          val replica = remaining.head // safe
          getBucketVc(replica._1)
          goto (Sent) using data.copy(buckets=remaining, progress=pr.copy(done=pr.done+1))
      }
    case Event(ReplFailed(), _) =>
      log.info("replication: skipped with timeout")
      stop()
  }

  def getBucketVc(b: Bucket): Unit = {
    actorMem.get(local, "ring_readonly_store").fold(
      _ ! ReplGetBucketVc(b),
      _ ! ReplGetBucketVc(b),
    )
  }
}

import ReplicationWorker.{ReplState}

object ReplicationWorker {
  final case class ReplState(prefList: PreferenceList, info: Seq[Seq[Data]], vc: VectorClock)

  def props(b: Bucket, prefList: PreferenceList, vc: VectorClock): Props = Props(new ReplicationWorker(b, prefList, vc))

  def mergeBucketData(l: Seq[Data]): Seq[Data] = mergeBucketData(l, merged=HashMap.empty)

  //todo: Key Map Seq[Data] to save conflicts
  @tailrec
  private def mergeBucketData(l: Seq[Data], merged: HashMap[ByteString,Data]): immutable.Seq[Data] = l match {
    case h +: t =>
      val hvc = makevc(h.vc)
      merged.get(h.key) match {
        case Some(d) if hvc == makevc(d.vc) && h.lastModified > d.lastModified =>
          mergeBucketData(t, merged + (h.key -> h))
        case Some(d) if hvc > makevc(d.vc) =>
          mergeBucketData(t, merged + (h.key -> h))
        case Some(_) => mergeBucketData(t, merged)
        case None => mergeBucketData(t, merged + (h.key -> h))
      }
    case xs if xs.isEmpty => merged.values.toVector
  }
}

class ReplicationWorker(b: Bucket, _prefList: PreferenceList, _vc: VectorClock) extends FSM[FsmState, ReplState] with ActorLogging {
  import ReplicationWorker.mergeBucketData
  import context.system
  val cluster = Cluster(system)
  val local = cluster.selfAddress
  val actorMem = SelectionMemorize(system)

  setTimer("send_by_timeout", OpsTimeout, Duration.fromNanos(context.system.settings.config.getDuration("ring.gather-timeout-replication").toNanos), repeat=false)
  startWith(Collecting, ReplState(_prefList, info=Nil, _vc))

  when(Collecting){
    case Event(ReplNewerBucketData(vc, items), data) =>
      val l: Seq[Data] = items.flatMap(_.data) //todo: replace `l` with `items`
      data.prefList - addr(sender) match {
        case empty if empty.isEmpty =>
          val all = data.info.foldLeft(l)((acc, list) => list ++ acc)
          val merged = mergeBucketData(all)
          actorMem.get(local, "ring_write_store").fold(
            _ ! ReplBucketPut(b, merged, fromvc(data.vc)),
            _ ! ReplBucketPut(b, merged, fromvc(data.vc)),
          )
          context.parent ! b
          stop()
        case nodes =>
          stay() using data.copy(
            prefList = nodes, 
            info = l +: data.info, 
            vc = data.vc merge makevc(vc),
          )
      }

    case Event(ReplBucketUpToDate(), data) =>
      data.prefList - addr(sender) match {
        case empty if empty.isEmpty =>
          val all = data.info.foldLeft(Nil: Seq[Data])((acc, list) => list ++ acc)
          val merged = mergeBucketData(all)
          actorMem.get(local, "ring_write_store").fold(
            _ ! ReplBucketPut(b, merged, fromvc(data.vc)),
            _ ! ReplBucketPut(b, merged, fromvc(data.vc)),
          )
          context.parent ! b
          stop()
        case nodes => stay() using data.copy(prefList=nodes)
      }

    case Event(OpsTimeout, data) =>
      log.warning(s"replication: timeout. downing=${data.prefList}")
      data.prefList.map(cluster.down)
      context.parent ! ReplFailed()
      stop()
  }

  def addr(s: ActorRef): Node = s.path.address

  initialize()
}
