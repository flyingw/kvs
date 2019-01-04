package mws.kvs

import mws.rng._
import org.scalatest._
import mws.rng.data._

class MergeTest extends FreeSpecLike with Matchers with EitherValues with BeforeAndAfterAll {
  def vc1(v: Long): Vec = Vec("n1", v) 
  def vc2(v: Long): Vec = Vec("n2", v) 

  "merge buckets" - {
    import mws.rng.MergeOps.forRepl
    "empty" in {
      val xs = Vector.empty
      forRepl(xs) should be (empty)
    }
    "single item" in {
      val xs = Vector(
        Data(stob("k1"), bucket=1, lastModified=1, vc=Vector(vc1(1), vc2(1)), stob("v1")),
      )
      val ys = Set(
        xs(0),
      )
      forRepl(xs).toSet should be (ys)
    }
    "no conflict" in {
      val xs = Vector(
        Data(stob("k1"), bucket=1, lastModified=1, vc=Vector(vc1(1), vc2(1)), stob("v1")),
        Data(stob("k2"), bucket=1, lastModified=1, vc=Vector(vc1(1), vc2(1)), stob("v2")),
        Data(stob("k3"), bucket=1, lastModified=1, vc=Vector(vc1(1), vc2(1)), stob("v3")),
      )
      val ys = Set(
        xs(0),
        xs(1),
        xs(2),
      )
      forRepl(xs).toSet should be (ys)
    }
    "same vc" - {
      val vcs = Vector(vc1(1), vc2(1))
      "old then new" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=1, vcs, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=2, vcs, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vcs, stob("v2")),
        )
        val ys = Set(
          xs(1),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
      "new then old" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=2, vcs, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=1, vcs, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vcs, stob("v2")),
        )
        val ys = Set(
          xs(0),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
    }
    "new vc" - {
      val vc1s = Vector(vc1(1), vc2(1))
      val vc2s = Vector(vc1(2), vc2(2))
      "old then new" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=2, vc1s, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=1, vc2s, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vc1s, stob("v2")),
        )
        val ys = Set(
          xs(1),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
      "new then old" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=1, vc2s, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=2, vc1s, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vc1s, stob("v2")),
        )
        val ys = Set(
          xs(0),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
    }
    "conflict" - {
      val vc1s = Vector(vc1(1), vc2(2))
      val vc2s = Vector(vc1(2), vc2(1))
      "seq" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=2, vc1s, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=1, vc2s, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vc1s, stob("v2")),
        )
        val ys = Set(
          xs(0),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
      "reversed" in {
        val xs = Vector(
          Data(stob("k1"), bucket=1, lastModified=1, vc2s, stob("v11")),
          Data(stob("k1"), bucket=1, lastModified=2, vc1s, stob("v12")),
          Data(stob("k2"), bucket=1, lastModified=1, vc1s, stob("v2")),
        )
        val ys = Set(
          xs(1),
          xs(2),
        )
        forRepl(xs).toSet should be (ys)
      }
    }
  }
}