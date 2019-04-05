package mws.kvs

object conf {
  def tmpl(port: Int) = s"""
    |akka {
    |  loglevel = off
    |  
    |  remote {
    |    netty.tcp {
    |      hostname = 127.0.0.1
    |      port = ${port}
    |    }
    |  }
    |
    |  cluster {
    |    seed-nodes = [
    |      "akka.tcp://Test@127.0.0.1:${port}",
    |    ]
    |  }
    |}
    |
    |ring.leveldb.dir = "rng_data_test_${port}"
  """.stripMargin
}