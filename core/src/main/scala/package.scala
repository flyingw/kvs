package zd

import zd.proto.api.{MessageCodec, encode, decode}
import zd.proto.Bytes
import java.util.Arrays

package object kvs {
  type Res[A] = Either[Err, A]

  implicit class BytesExt(x: Bytes) {
    def splitAt(n: Int): (Bytes, Bytes) = {
      val res = x.unsafeArray.splitAt(n)
      (Bytes.unsafeWrap(res._1), Bytes.unsafeWrap(res._2))
    }
    def length: Int = x.unsafeArray.length
    def increment(): Bytes = {
      val len = x.length
      if (length == 0) BytesExt.MinValue
      else {
        val last = x.unsafeArray(len-1)
        if (last == Byte.MaxValue) {
          val ext = Arrays.copyOf(x.unsafeArray, len+1)
          ext(len) = Byte.MinValue
          Bytes.unsafeWrap(ext)
        } else {
          val ext: Array[Byte] = Arrays.copyOf(x.unsafeArray, len)
          ext(len-1) = (ext(len-1) + 1).toByte
          Bytes.unsafeWrap(ext)
        }
      }
    }
    def decrement(): Bytes = {
      val len = x.length
      if (length == 0) BytesExt.Empty
      else {
        val last = x.unsafeArray(len-1)
        if (last == Byte.MinValue) {
          if (length == 1) BytesExt.Empty
          else {
            val ext = Arrays.copyOf(x.unsafeArray, len-1)
            Bytes.unsafeWrap(ext)
          }
        } else {
          val ext: Array[Byte] = Arrays.copyOf(x.unsafeArray, len)
          ext(len-1) = (ext(len-1) - 1).toByte
          Bytes.unsafeWrap(ext)
        }
      }
    }
  }

  object BytesExt {
    val Empty: Bytes = Bytes.unsafeWrap(Array())
    val MinValue: Bytes = Bytes.unsafeWrap(Array(Byte.MinValue))
    def max(x: Bytes, y: Bytes): Bytes = {
      if (Arrays.compare(x.unsafeArray, y.unsafeArray) > 0) x else y
    }
  }

  implicit class FdIdExt(fd: en.FdId) {
    def +:(data: Bytes)(implicit kvs: Kvs): Res[en.IdEn] = en.EnHandler.prepend(fd.id, data)(kvs.dba)
    def +:(id_data: (Bytes, Bytes))(implicit kvs: Kvs): Res[en.En] = en.EnHandler.prepend(fd.id, id_data._1, id_data._2)(kvs.dba)
  }

  def pickle[A](e: A)(implicit c: MessageCodec[A]): Bytes = Bytes.unsafeWrap(encode[A](e))
  def unpickle[A](a: Bytes)(implicit c: MessageCodec[A]): A = decode[A](a.unsafeArray)
}