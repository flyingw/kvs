package zd.kvs
package search

import zd.kvs.en.{En, EnHandler, feedHandler}
import zd.proto.api.{N, encode, decode}
import zd.proto.macrosapi.caseCodecAuto
import zd.gs.z._

final case class IndexFile
  ( @N(1) fid: String
  , @N(2) id: String
  , @N(3) prev: String = empty
  ) extends En

object IndexFileHandler extends EnHandler[IndexFile] {
  override val fh = feedHandler
  override protected def update(en: IndexFile, id: String, prev: String): IndexFile = en.copy(id = id, prev = prev)
  override protected def update(en: IndexFile, prev: String): IndexFile = en.copy(prev = prev)
  
  private[this] implicit val codec = caseCodecAuto[IndexFile]
  def pickle(e: IndexFile): Res[Array[Byte]] = encode(e).right
  def unpickle(a: Array[Byte]): Res[IndexFile] = decode[IndexFile](a).right
}