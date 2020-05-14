package zd.kvs
package rng

import akka.actor._
import akka.cluster.ClusterEvent._
import akka.cluster.{Member, Cluster}
import com.typesafe.config.Config
import zd.kvs.rng.model.{StoreDelete, StoreGet, QuorumState, ChangeState}
import zd.kvs.rng.model.QuorumState.{QuorumStateUnsatisfied, QuorumStateReadonly, QuorumStateEffective}
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._
import zero.ext._, option._
import zd.proto.Bytes
import zd.proto.api.N

final case class Put(k: Bytes, v: Bytes)
final case class Get(k: Bytes)
final case class Delete(k: Bytes)

final case class Save(path: String)
final case class Load(path: String)

final case object RestoreState

final case object Ready

final case class InternalPut(k: Bytes, v: Bytes)

final case class HashRngData(
  nodes: Set[Node],
  buckets: SortedMap[Bucket, PreferenceList],
  vNodes: SortedMap[Bucket, Node],
  replication: Option[ActorRef],
)

final case class PortVNode
  (
    @N(1) port: String
  , @N(2) vnode: Int
  )

object Hash {
  def props(): Props = Props(new Hash)
}

class Hash extends FSM[QuorumState, HashRngData] with ActorLogging {
  import context.system

  val config: Config = system.settings.config.getConfig("ring")

  val quorum = config.getIntList("quorum")
  val N: Int = quorum.get(0)
  val W: Int = quorum.get(1)
  val R: Int = quorum.get(2)
  val gatherTimeout = Duration.fromNanos(config.getDuration("gather-timeout").toNanos)
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

  startWith(QuorumStateUnsatisfied, HashRngData(Set.empty[Node], SortedMap.empty[Bucket, PreferenceList], SortedMap.empty[Bucket, Node], replication=None))

  override def preStart() = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved])
  }
  
  override def postStop(): Unit = cluster.unsubscribe(self)

  when(QuorumStateUnsatisfied){
    case Event(_: Get, _) =>
      sender ! AckQuorumFailed("QuorumStateUnsatisfied")
      stay()
    case Event(_: Put, _) =>
      sender ! AckQuorumFailed("QuorumStateUnsatisfied")
      stay()
    case Event(_: Delete, _) =>
      sender ! AckQuorumFailed("QuorumStateUnsatisfied")
      stay()
    case Event(Save(_), _) =>
      sender ! AckQuorumFailed("QuorumStateUnsatisfied")
      stay()
    case Event(Load(_), _) =>
      sender ! AckQuorumFailed("QuorumStateUnsatisfied")
      stay()
    case Event(RestoreState, _) =>
      log.warning("Don't know how to restore state when quorum is unsatisfied")
      stay()
    case Event(Ready, _) =>
      sender ! false
      stay()
  }

  when(QuorumStateReadonly){
    case Event(x: Get, data) =>
      doGet(x.k, sender, data)
      stay()
    case Event(RestoreState, data) =>
      val s = state(data.nodes.size)
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(s), 
        _ ! ChangeState(s),
      ))
      goto(s)
    case Event(Ready, _) =>
      sender ! false
      stay()

    case Event(_: Put, _) =>
      sender ! AckQuorumFailed("QuorumStateReadonly")
      stay()
    case Event(_: Delete, _) =>
      sender ! AckQuorumFailed("QuorumStateReadonly")
      stay()
    case Event(Save(_), _) =>
      sender ! AckQuorumFailed("QuorumStateReadonly")
      stay()
    case Event(Load(_), _) =>
      sender ! AckQuorumFailed("QuorumStateReadonly")
      stay()
  }

  when(QuorumStateEffective){
    case Event(Ready, _) =>
      sender ! true
      stay()

    case Event(x: Get, data) =>
      doGet(x.k, sender, data)
      stay()
    case Event(x: Put, data) =>
      doPut(x.k, x.v, sender, data)
      stay()
    case Event(x: Delete, data) =>
      doDelete(x.k, sender, data)
      stay()

    case Event(Save(path), data) =>
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(QuorumStateReadonly),
        _ ! ChangeState(QuorumStateReadonly),
      ))
      val x = system.actorOf(DumpProcessor.props(), s"dump_wrkr-${now_ms()}")
      x.forward(DumpProcessor.Save(data.buckets, path))
      goto(QuorumStateReadonly)
    case Event(Load(path), data) =>
      data.nodes.foreach(n => actorsMem.get(n, "ring_hash").fold(
        _ ! ChangeState(QuorumStateReadonly),
        _ ! ChangeState(QuorumStateReadonly),
      ))
      val x = system.actorOf(DumpProcessor.props(), s"load_wrkr-${now_ms()}")
      x.forward(DumpProcessor.Load(path))
      goto(QuorumStateReadonly)
    case Event(RestoreState, _) =>
      log.info("State is already OK")
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
    case Event(ChangeState(s), data) =>
      state(data.nodes.size) match {
        case QuorumStateUnsatisfied => stay()
        case _ => goto(s)
      }
    case Event(x: InternalPut, data) =>
      doPut(x.k, x.v, sender, data)
      stay()
  }

  def doDelete(k: Bytes, client: ActorRef, data: HashRngData): Unit = {
    val nodes = nodesForKey(k, data)
    val gather = system.actorOf(GatherDel.props(client, gatherTimeout, nodes, k))
    val stores = nodes.map{actorsMem.get(_, "ring_write_store")}
    stores.foreach(_.fold(
      _.tell(StoreDelete(k), gather), 
      _.tell(StoreDelete(k), gather),
    ))
  }

  def doPut(k: Bytes, v: Bytes, client: ActorRef, data: HashRngData): Unit = {
    val nodes = availableNodesFrom(nodesForKey(k, data))
    val M = nodes.size
    if (M >= W) {
      val bucket = hashing.findBucket(k)
      val info = PutInfo(k, v, N, W, bucket, local, data.nodes)
      val gather = system.actorOf(GatherPut.props(client, gatherTimeout, info))
      val node = if (nodes contains local) local else nodes.head
      actorsMem.get(node, "ring_readonly_store").fold(
        _.tell(StoreGet(k), gather),
        _.tell(StoreGet(k), gather),
      )
    } else {
      client ! AckQuorumFailed("M >= W")
    }
  }

  def doGet(k: Bytes, client: ActorRef, data: HashRngData): Unit = {
    val nodes = availableNodesFrom(nodesForKey(k, data))
    val M = nodes.size
    if (M >= R) {
      val gather = system.actorOf(GatherGet.props(client, gatherTimeout, M, R, k))
      val stores = nodes map { actorsMem.get(_, "ring_readonly_store") }
      stores foreach (_.fold(
        _.tell(StoreGet(k), gather),
        _.tell(StoreGet(k), gather),
      ))
    } else {
      client ! AckQuorumFailed("M >= R")
    }
  }

  def availableNodesFrom(l: Set[Node]): Set[Node] = {
    val unreachableMembers = cluster.state.unreachable.map(m => m.address)
    l filterNot (node => unreachableMembers contains node)
  }

  def joinNodeToRing(member: Member, data: HashRngData): (QuorumState, HashRngData) = {
    val newvNodes: Map[VNode, Node] = (1 to vNodesNum).view.map(vnode =>
      hashing.hash(pickle(PortVNode(port=member.address.hostPort, vnode=vnode))) -> member.address
    ).to(Map)
    val updvNodes = data.vNodes ++ newvNodes
    val nodes = data.nodes + member.address
    val moved = bucketsToUpdate(bucketsNum - 1, Math.min(nodes.size,N), updvNodes, data.buckets)
    data.replication map (context stop _)
    val repl = syncNodes(moved)
    val updData = HashRngData(nodes, data.buckets++moved, updvNodes, repl.some)
    log.info(s"Node ${member.address} is joining ring. Nodes in ring = ${updData.nodes.size}, state = ${state(updData.nodes.size)}")
    state(updData.nodes.size) -> updData
  }

  def removeNodeFromRing(member: Member, data: HashRngData): (QuorumState, HashRngData) = {
    log.info(s"Removing ${member} from ring")
    val unusedvNodes: Set[VNode] = (1 to vNodesNum).view.map(vnode =>
      hashing.hash(pickle(PortVNode(port=member.address.hostPort, vnode=vnode)))
    ).to(Set)
    val updvNodes = data.vNodes.filterNot(vn => unusedvNodes.contains(vn._1))
    val nodes = data.nodes - member.address
    val moved = bucketsToUpdate(bucketsNum - 1, Math.min(nodes.size,N), updvNodes, data.buckets)
    log.info(s"Will update ${moved.size} buckets")
    data.replication map (context stop _)
    val repl = syncNodes(moved)
    val updData = HashRngData(nodes, data.buckets++moved, updvNodes, repl.some)
    state(updData.nodes.size) -> updData
  }

  def itob(v: Int): Array[Byte] = Array[Byte]((v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte)

  def syncNodes(_buckets: SortedMap[Bucket,PreferenceList]): ActorRef = {
    val empty = SortedMap.empty[Bucket,PreferenceList]
    val buckets = _buckets.foldLeft(empty){ case (acc, (b, prefList)) =>
      if (prefList contains local) {
        prefList.filterNot(_ == local) match {
          case empty if empty.isEmpty => acc
          case prefList => acc + (b -> prefList)
        }
      } else acc
    }
    val replication = context.actorOf(ReplicationSupervisor.props(buckets), s"repl-${now_ms()}")
    replication ! "go-repl"
    replication
  }

  def state(nodes: Int): QuorumState = nodes match {
    case 0 => QuorumStateUnsatisfied
    case n if n >= Math.max(R, W) => QuorumStateEffective
    case _ => QuorumStateReadonly
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
  
  def nodesForKey(k: Bytes, data: HashRngData): PreferenceList = data.buckets.get(hashing.findBucket(k)) match {
    case None => SortedSet.empty[Node]
    case Some(nods) => nods
  }

  initialize()
}