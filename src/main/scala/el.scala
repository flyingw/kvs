package mws.kvs
package el

import scalaz._, Scalaz._
import store._

trait ElHandler[T] {
  def pickle(e: T): Res[Array[Byte]]
  def unpickle(a: Array[Byte]): Res[T]

  def put(k:String,el:T)(implicit dba:Dba):Res[T] = pickle(el).flatMap(x => dba.put(k,x)).map(_=>el)
  def get(k:String)(implicit dba:Dba):Res[T] = dba.get(k).flatMap(unpickle)
  def delete(k:String)(implicit dba:Dba):Res[T] = dba.delete(k).flatMap(unpickle)
}

object ElHandler {
  implicit object bytesHandler extends ElHandler[Array[Byte]]{
    def pickle(e:Array[Byte]):Res[Array[Byte]] = e.right
    def unpickle(a:Array[Byte]):Res[Array[Byte]] = a.right
  }
  implicit object strHandler extends ElHandler[String]{
    def pickle(e:String):Res[Array[Byte]] = e.getBytes("UTF-8").right
    def unpickle(a:Array[Byte]):Res[String] = new String(a,"UTF-8").right
  }
}
