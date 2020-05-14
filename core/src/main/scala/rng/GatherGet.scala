package zd.kvs
package rng

import annotation.unused
import akka.actor._
import zd.kvs.rng.data.Data
import zd.kvs.rng.model.{StoreGetAck, StorePut, StoreDelete}
import scala.concurrent.duration._
import scala.collection.immutable.{HashSet}
import zd.proto.Bytes

import GatherGet.DataCollection

class GatherGet(client: ActorRef, t: FiniteDuration, M: Int, @unused R: Int, k: Bytes) extends FSM[FsmState, DataCollection] with ActorLogging {
  val stores = SelectionMemorize(context.system)

  startWith(Collecting, DataCollection(Vector.empty, 0))
  setTimer("send_by_timeout", "timeout", t)

  when(Collecting) {
    case Event(StoreGetAck(key, bucket, data), DataCollection(perNode, nodes)) =>
      val xs = (data -> addr(sender)) +: perNode
      nodes + 1 match {
        case `M` => // alternative is to wait for first R same answers
          cancelTimer("send_by_timeout")
          val (correct: Option[Data], outdated: HashSet[Node]) = MergeOps.forGatherGet(xs)
          ;{ // update outdated nodes with correct data
            val msg = correct.fold[Any](StoreDelete(k))(d => StorePut(key, bucket, d))
            outdated foreach { node =>
              stores.get(node, "ring_write_store").fold(_ ! msg, _ ! msg)
            }
          }
          client ! AckSuccess(correct.map(_.value))
          stop()
        case ns =>
          stay using DataCollection(xs, ns)
      }

    case Event("timeout", _) =>
      client ! AckTimeoutFailed("get", k)
      stop()
  }
}

object GatherGet {
  def props(client: ActorRef, t: FiniteDuration, M: Int, R: Int, k: Bytes): Props = Props(new GatherGet(client, t, M, R, k))

  type AddrOfData = (Option[Data], Node)

  final case class DataCollection(perNode: Vector[AddrOfData], nodes: Int)
}