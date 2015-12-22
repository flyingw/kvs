package mws.kvs
package handle

import store._

import scala.pickling._

import scala.language.postfixOps

/**
 * KVS Handler for specific type T.
 * object Handler holds implicit default handlers.
 */
trait Handler[T] {
  def pickle(e:T):Array[Byte]
  def unpickle(a:Array[Byte]):T

  // todo: consider to be private methods
  def put(el:T)(implicit dba:Dba):Res[T]
  def get(k:String)(implicit dba:Dba):Res[T]
  def delete(k:String)(implicit dba:Dba):Res[T]

  // container/iterator API
  def add(el:T)(implicit dba:Dba):Res[T]
  def remove(el:T)(implicit dba:Dba):Res[T]
  def entries(fid:String,from:Option[T],count:Option[Int])(implicit dba:Dba):Res[List[T]]
  def entries(fid:String)(implicit dba:Dba):Res[List[T]] = entries(fid,None,None)
}

object Handler {
  def apply[T](implicit h:Handler[T]) = h

  /**
   * The basic feed/entry handlers with scala-pickling serialization
   */
  import scala.pickling._, binary._, Defaults._

  implicit object feedHandler extends FdHandler
  implicit object enStrHandler extends EnHandler[String]{
    def pickle(e:En[String]) = e.pickle.value
    def unpickle(a:Array[Byte]) = a.unpickle[En[String]]
  }

  import scalaz._,Scalaz._

  /**
   * Given handler S create the handler for T from conversion functions.
   */
  def by[A,S](f:A => S)(g:S => A)(key:String=>String)(implicit h:Handler[S]):Handler[A] = new Handler[A] {
    def pickle(e: A): Array[Byte] = h.pickle(f(e))
    def unpickle(a: Array[Byte]): A = g(h.unpickle(a))

    def put(el:A)(implicit dba:Dba):Res[A] = h.put(f(el)).right.map(g)
    def get(k:String)(implicit dba:Dba):Res[A] = h.get(key(k)).right.map(g)
    def delete(k:String)(implicit dba:Dba):Res[A] = h.delete(key(k)).right.map(g)

    def add(el:A)(implicit dba:Dba):Res[A] = h.add(f(el)).right.map(g)
    def remove(el:A)(implicit dba:Dba):Res[A] = h.remove(f(el)).right.map(g)
    def entries(fid:String,from:Option[A],count:Option[Int])(implicit dba:Dba):Res[List[A]] =
      h.entries(fid,toOpt(from),count).right.map { _ map g }

    def by[C,D](f:C=>D)(g:D=>C)(key:String => String) = this
    def toOpt = Functor[Option].lift(f)
  }

}