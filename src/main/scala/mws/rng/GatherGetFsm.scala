package mws.rng

import akka.actor.FSM.Normal
import akka.actor._
import scala.annotation.tailrec
import scala.concurrent.duration._

class GatherGetFsm(client: ActorRef, N: Int, R: Int, t: Int, refResolver: ActorRefStorage)
  extends FSM[FsmState, FsmData] with ActorLogging{
  
  startWith(Collecting, DataCollection(Nil, Nil))
  setTimer("send_by_timeout", GatherTimeout, t.seconds)

  def fixInconsistency(correct: Data, fromNodes: DataCollection) = {
    fromNodes.perNode collect {
      case (Some(d), n) if d.vc == correct.vc =>
        refResolver.get(n, "/user/write_store").fold(
          _ ! StorePut(correct),
          _ ! StorePut(correct))
    }
    fromNodes.inconsistent.foreach {
      case (l, n) => refResolver.get(n, "/user/write_store").fold(
        _ ! StorePut(correct),
        _ ! StorePut(correct))
    }
  }

  when(Collecting) {
    case Event(GetResp(rez), state@DataCollection(perNode, inconsistent)) =>
      updateFSMState(rez, state, sender().path.address).get match {
        case d @ DataCollection(l, inc) if l.size == R =>
          val response = mostFresh(l.map(_._1).flatten)
          client ! response.map(_.value)
          goto(Sent) using d
        case d @ DataCollection(l, inc) if d.size == N => // fuck          
          val response = mostFresh(l.map(_._1).flatten ::: inc.foldLeft(List.empty[Data])((acc, ll) => ll._1 ::: acc))
          client ! response.map(_.value)
          response.foreach(fixInconsistency(_, d))
          cancelTimer("send_by_timeout")
          stop()
        case d => stay() using d
      }
      
    case Event(GatherTimeout, DataCollection(l, inc)) =>
      val response = mostFresh(l.map(_._1).flatten ::: inc.foldLeft(List.empty[Data])((acc, ll) => ll._1 ::: acc))
      client ! response.map(_.value)      
      // cannot fix inconsistent because quorum not satisfied. But check what can do
      cancelTimer("send_by_timeout")
      stop()
  } 
  
  when(Sent) {
    case Event(GetResp(rez), state@DataCollection(l, inc)) =>
      val newState = updateFSMState(rez, state, sender().path.address).get
      if (newState.size == N) {
        val response = mostFresh(l.map(_._1).flatten ::: inc.foldLeft(List.empty[Data])((acc, ll) => ll._1 ::: acc))
        response.foreach(fixInconsistency(_, state))
        cancelTimer("send_by_timeout")      
        stop()
      } else {
        stay() using newState
      }     
      
    case Event(GatherTimeout, state @ DataCollection(l, inc) ) =>
      val response = mostFresh(l.map(_._1).flatten ::: inc.foldLeft(List.empty[Data])((acc, ll) => ll._1 ::: acc))
      response.foreach(fixInconsistency(_, state))
      cancelTimer("send_by_timeout")
      stop(Normal)
  }

  def updateFSMState(rez: Option[List[Data]], old: DataCollection, sender: Node) = rez map {
    case Nil => DataCollection((None, sender) :: old.perNode, old.inconsistent)
    case l if l.size == 1 => DataCollection((Some(l.head), sender) :: old.perNode, old.inconsistent)
    case l => DataCollection(old.perNode, (l, sender) :: old.inconsistent)
  }

  def mostFresh(from: List[Data]): Option[Data] = {
    @tailrec
    def iterate(data: List[Data], mostFresh: Option[Data] = None): Option[Data] = data match {
      case Nil => mostFresh      
      case head :: tail =>
        val newest: Option[Data] = mostFresh match {
          case None => Some(head)
          case Some(currFresh) if currFresh.vc == head.vc || currFresh.vc > head.vc => mostFresh
          case Some(currFresh) if currFresh.vc < head.vc => Some(head)
          case Some(currFresh) if currFresh.vc <> head.vc => //last write win
            if (currFresh.lastModified > head.lastModified) mostFresh
            else Some(head)
        }
        iterate(tail, newest)
    }
    iterate(from)
  }
}
