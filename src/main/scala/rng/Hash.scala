package mws.rng

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Member, Cluster}
import akka.util.Timeout
import com.google.protobuf.ByteString
import com.typesafe.config.Config
import mws.rng.msg.{StoreDelete, StoreGet}
import mws.rng.store._
import scala.collection.{SortedMap, SortedSet, breakOut}
import scala.concurrent.duration._

sealed class APIMessage
//kvs
case class Put(k: Key, v: Value) extends APIMessage
case class Get(k: Key) extends APIMessage
case class Delete(k: Key) extends APIMessage
case class Dump(dumpLocation: String) extends APIMessage
case class LoadDump(dumpPath: String, javaSer: Boolean) extends APIMessage
case class IterateDump(dumpPath: String, foreach: (ByteString, ByteString) => Unit) extends APIMessage
case object RestoreState extends APIMessage
//utilities
case object Ready
case class ChangeState(s: QuorumState)
case class InternalPut(k: Key, v: Value)

sealed trait QuorumState
case object Unsatisfied extends QuorumState
case object Readonly extends QuorumState
case object Effective extends QuorumState
//  WriteOnly and WeakReadonly are currently ignored because rng is always readonly
case object WriteOnly extends QuorumState
case object WeakReadonly extends QuorumState

case class HashRngData(nodes: Set[Node],
                  buckets: SortedMap[Bucket, PreferenceList],
                  vNodes: SortedMap[Bucket, Node])
// TODO available/not avaiable nodes
class Hash extends FSM[QuorumState, HashRngData] with ActorLogging {
  import context.system
  implicit val timeout = Timeout(5 seconds)

  val config: Config = system.settings.config.getConfig("ring")

  val quorum = config.getIntList("quorum")
  val N: Int = quorum.get(0)
  val W: Int = quorum.get(1)
  val R: Int = quorum.get(2)
  val gatherTimeout = config.getInt("gather-timeout")
  val vNodesNum = config.getInt("virtual-nodes")
  val bucketsNum = config.getInt("buckets")
  val cluster = Cluster(system)
  val local: Node = cluster.selfAddress
  val hashing = HashingExtension(system)
  val actorsMem = SelectionMemorize(system)

  log.info(s"Ring configuration:".blue)
  log.info(s"ring.quorum.N = ${N}".blue)
  log.info(s"ring.quorum.W = ${W}".blue)
  log.info(s"ring.quorum.R = ${R}".blue)
  log.info(s"ring.leveldb.dir = ${config.getString("leveldb.dir")}".blue)

  startWith(Unsatisfied, HashRngData(Set.empty[Node], SortedMap.empty[Bucket, PreferenceList], SortedMap.empty[Bucket, Node]))

  override def preStart() = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved])
  }
  override def postStop(): Unit = cluster.unsubscribe(self)

  when(Unsatisfied){
    case Event(ignoring: APIMessage, _) =>
      log.debug(s"Not enough nodes to process ${cluster.state}: ${ignoring}")
      stay()
  }

  when(Readonly){
    case Event(Get(k), data) =>
      doGet(k, sender, data)
      stay()
    case Event(RestoreState, data) =>
      val s = state(data.nodes.size)
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(s), 
        _ ! ChangeState(s)
      ))
      goto(s)
    case Event(Ready, _) =>
      sender ! false
      stay()
  }

  when(Effective){
    case Event(Ready, _) =>
      sender ! true
      stay()
    case Event(Get(k), data) =>
      val s = sender
      doGet(k, s, data)
      stay()
    case Event(Put(k,v), data) =>
      val s = sender
      doPut(k, v, s, data)
      stay()
    case Event(Delete(k), data) =>
      val s = sender
      doDelete(k, s, data)
      stay()
    case Event(Dump(path), data) =>
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(Readonly),
        _ ! ChangeState(Readonly),
      ))
      system.actorOf(DumpProcessor.props, s"dump_wrkr-${System.currentTimeMillis}").forward(DumpProcessor.SaveDump(data.buckets, local, path))
      goto(Readonly)
    case Event(LoadDump(dumpPath, javaSer), data) =>
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(Readonly),
        _ ! ChangeState(Readonly),
      ))
      if (javaSer) {
        system.actorOf(LoadDumpWorkerJava.props(dumpPath), s"load_wrkr-${System.currentTimeMillis}").forward(LoadDump(dumpPath, true))
      } else {
        system.actorOf(DumpProcessor.props, s"load_wrkr-${System.currentTimeMillis}").forward(DumpProcessor.LoadDump(dumpPath))
      }
      goto(Readonly)
    case Event(IterateDump(dumpPath, foreach), data) =>
      system.actorOf(IterateDumpWorker.props(dumpPath,foreach), s"iter_wrkr-${System.currentTimeMillis}").forward(IterateDump(dumpPath,foreach))
      stay()
  }

  /* COMMON FOR ALL STATES*/
  whenUnhandled {
    case Event(MemberUp(member), data) =>
      val next = joinNodeToRing(member, data)
      goto(next._1) using next._2
    case Event(MemberRemoved(member, prevState), data) =>
      val next = removeNodeFromRing(member, data)
      goto(next._1) using next._2
    case Event(Ready, data) =>
      sender ! false
      stay()
    case Event(ChangeState(s), data) =>
      if(state(data.nodes.size) == Unsatisfied) stay() else goto(s)
    case Event(InternalPut(k, v), data) =>
      doPut(k, v, sender, data)
      stay()
  }

def doDelete(k: Key, client: ActorRef, data: HashRngData): Unit = {
  val nodes = nodesForKey(k, data)
  val gather = system.actorOf(Props(classOf[GathererDel], nodes, client))
  val stores = nodes.map{actorsMem.get(_, "ring_write_store")}
  stores.foreach(s => s.fold(
    _.tell(StoreDelete(k), gather), 
    _.tell(StoreDelete(k), gather),
  ))
}

def doPut(k: Key, v: Value, client: ActorRef, data: HashRngData):Unit = {
  val bucket = hashing findBucket k
  val nodes = availableNodesFrom(nodesForKey(k, data))
  if (nodes.size >= W) {
    val info = PutInfo(k, v, N, W, bucket, local, data.nodes)
    val gather = system.actorOf(GatherPutFSM.props(client, gatherTimeout, actorsMem, info))
    val node = ??? // nodes.getOrElse(local, nodes.head)
    actorsMem.get(node, "ring_readonly_store").fold(
      _.tell(StoreGet(k), gather),
      _.tell(StoreGet(k), gather),
    )
  } else {
    client ! AckQuorumFailed
  }
 }

 def doGet(key: Key, client: ActorRef, data: HashRngData) : Unit = {
   val fromNodes = availableNodesFrom(nodesForKey(key, data))
   if (fromNodes.nonEmpty) {
     val gather = system.actorOf(Props(classOf[GatherGetFsm], client, fromNodes.size, R, key))
     val stores = fromNodes map { actorsMem.get(_, "ring_readonly_store") }
     stores foreach (store => store.fold(
       _.tell(StoreGet(key), gather),
       _.tell(StoreGet(key), gather),
     ))
   } else {
     client ! None
   }
 }

  def availableNodesFrom(l: Set[Node]): Set[Node] = {
    val unreachableMembers = cluster.state.unreachable.map(m => m.address)
    l filterNot (node => unreachableMembers contains node)
  }

  def joinNodeToRing(member: Member, data: HashRngData): (QuorumState, HashRngData) = {
      val newvNodes: Map[VNode, Node] = (1 to vNodesNum).map(vnode => {
        hashing.hash(stob(member.address.hostPort).concat(itob(vnode))) -> member.address})(breakOut)
      val updvNodes = data.vNodes ++ newvNodes
      val nodes = data.nodes + member.address
      val moved = bucketsToUpdate(bucketsNum - 1, Math.min(nodes.size,N), updvNodes, data.buckets)
      synchNodes(moved)
      val updData:HashRngData = HashRngData(nodes, data.buckets++moved,updvNodes)
      log.info(s"[rng] Node ${member.address} is joining ring. Nodes in ring = ${updData.nodes.size}, state = ${state(updData.nodes.size)}")
      (state(updData.nodes.size), updData)
  }

  def removeNodeFromRing(member: Member, data: HashRngData) : (QuorumState, HashRngData) = {
      log.info(s"[ring_hash]Removing $member from ring")
      val unusedvNodes: Map[VNode, Node] = (1 to vNodesNum).map(vnode => {
      hashing.hash(stob(member.address.hostPort).concat(itob(vnode))) -> member.address})(breakOut)
      val updvNodes = data.vNodes.filterNot(vn => unusedvNodes.contains(vn._1))
      val nodes = data.nodes + member.address
      val moved = bucketsToUpdate(bucketsNum - 1, Math.min(nodes.size,N), updvNodes, data.buckets)
      log.info(s"WILL UPDATE ${moved.size} buckets")
      val updData: HashRngData = HashRngData(data.nodes + member.address, data.buckets++moved, updvNodes)
      synchNodes(moved)
      (state(updData.nodes.size), updData)
  }

  def synchNodes(buckets: SortedMap[Bucket, PreferenceList]): Unit = {
    val repl = buckets.foldLeft(SortedMap.empty[Bucket,PreferenceList])((acc, b_prefList) =>
      if(b_prefList._2.contains(local)) acc+ (b_prefList._1 ->  b_prefList._2.filterNot(_ == local)) else acc)
    context.actorOf(Props(classOf[ReplicationSupervisor], repl), s"repl-${System.currentTimeMillis()}") ! "go-repl"
  }

  def state(nodes : Int): QuorumState = nodes match {
    case 0 => Unsatisfied
    case n if n >= Seq(R,W).max => Effective
    case _ => Readonly
  }

  def bucketsToUpdate(maxBucket: Bucket, nodesNumber: Int, vNodes: SortedMap[Bucket, Node], buckets: SortedMap[Bucket, PreferenceList]): SortedMap[Bucket, PreferenceList] = {
    (0 to maxBucket).foldLeft(SortedMap.empty[Bucket, PreferenceList])((acc, b) => {
       val prefList = hashing.findNodes(b * hashing.bucketRange, vNodes, nodesNumber)
      buckets.get(b) match {
      case None => acc + (b -> prefList)
      case Some(`prefList`) => acc
      case _ => acc + (b -> prefList)
    }})
  }

  implicit val ord = Ordering.by[Node, String](n => n.hostPort)
  def nodesForKey(k: Key, data: HashRngData): PreferenceList = data.buckets.get(hashing.findBucket(k)) match {
    case None => SortedSet.empty[Node]
    case Some(nods) => nods
  }

  initialize()
}
