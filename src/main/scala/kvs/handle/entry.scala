package mws.kvs
package handle

import store._

import scala.language.postfixOps

/**
 * Abstract type entry handler.
 *
 * Since we don't know the exact type the pickler/unpickler still needs to be provided explicitly.
 */
trait EnHandler[T] extends Handler[En[T]]{
  import Handler._
  val fh = implicitly[Handler[Fd]]

  def put(el:En[T])     (implicit dba: Dba):Res[En[T]] = dba.put(el.id, pickle(el)).right.map{_=>el}
  def get(k: String)    (implicit dba: Dba):Res[En[T]] = dba.get(k).right.map(unpickle)
  def delete(k: String) (implicit dba: Dba):Res[En[T]] = dba.delete(k).right.map(unpickle)

  /**
   * Adds the entry to the container specified as id.
   * Creates the container with specified id if its absent.
   *
   * todo: merge top/el update error cases. top update failures now skipped
   */
  def add(el: En[T])(implicit dba: Dba): Res[En[T]] = {
    val eid = s"${el.fid}.${el.id}"

    fh.get(el.fid).left.map {
      case Dbe("error","not_found") => fh.put(new Fd(el.fid,None,0))
    }.joinLeft.right.map { feed:Fd =>
      get(eid).fold(_ =>
        put(el.copy(id=eid,prev=feed.top)).right.map { _ =>
          //update top
          feed.top.map {id=> get(s"${el.fid}.$id").right.map{a=> put(a.copy(next=Some(el.id))) }.joinRight }
          //update el
          fh.put(feed.copy(top=Some(el.id),count=feed.count+1)).
            fold(l => Left(l),r => Right(el.copy(prev=feed.top)))
        }.joinRight.swap,
        _ => Right(Dbe("error",s"entry ${el.id} exist in ${el.fid}"))
      ).swap
    }.joinRight
  }

  /**
   * Remove the entry from the container specified as id.
   * todo: check failure cases
   */
  def remove(el: En[T])(implicit dba: Dba): Res[En[T]] = {
    val id = s"${el.fid}.${el.id}"
    get(id).right.map { _ =>
      delete(id).fold(
        l => Left(l),
        r => r match { case En(fid,_,prev,next,_) =>
          prev map { get(_).right.map { p => put(p.copy(next=next)) }}
          next map { get(_).right.map { n => put(n.copy(prev=prev)) }}

          fh.get(fid).right.map { feed =>
            fh.put(feed.copy(top=prev,count=feed.count-1))
          }.joinRight.right.map{_ => el}
        })
    }.joinRight
  }

  /**
   * Iterate through container and return the list of entry with specified size.
   */
  import scalaz._, Scalaz._

  def entries(fid:String, from:Option[En[T]], count:Option[Int])(implicit dba:Dba):Res[List[En[T]]] = fh.get(fid).fold(
    l => Left(Dbe("error",s"$fid $l")),
    r => r match {case Fd(`fid`, top, size) =>

      val none:Res[En[T]] = Left(Dbe(msg="done"))
      def next:(Res[En[T]]) => Res[En[T]] = _.right.map {_.prev.fold(none)({id=> get(s"$fid.$id")})}.joinRight
      val start = (from orElse top).map{eid => s"${fid}.${eid}"}.get

      List.iterate(get(start), count.getOrElse(size))(next).sequenceU
  })
}