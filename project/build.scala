package kvs

import sbt._
import sbt.Keys._

object Versions {
  val scala = "2.12.0"
  val scalaz = "7.2.7"
  val pickling = "0.11.0-M2-11-gf9ea158"
  val akka = "2.4.12-21-g0983fdc"
  val xml = "1.0.5"
  val scalatest = "3.0.0"
  val leveldb = "1.8"
}

object Build extends sbt.Build {
  lazy val root = Project(
    id = "kvs",
    base = file("."),
    settings = Defaults.coreDefaultSettings ++ publishSettings ++ Seq(
      mainClass in (Compile,run) := Some("mws.kvs.Run"),
      cancelable in Global := true,
      fork in run := true,
      scalacOptions in Compile ++= Seq("-feature","-deprecation"/*,"-Xlog-implicits"*/),
      fork in Test := true,
      libraryDependencies ++= Seq(
        "org.scalaz" %% "scalaz-core" % Versions.scalaz,
        "com.playtech.mws" %% "scala-pickling" % Versions.pickling,
        "org.scala-lang" % "scala-compiler" % Versions.scala,
        "org.scala-lang.modules" %% "scala-xml" % Versions.xml,
        "org.fusesource.leveldbjni" % "leveldbjni-all" % Versions.leveldb,
        "org.scalatest" %% "scalatest" % Versions.scalatest % Test,
        "com.playtech.mws.akka" %% "akka-actor"              % Versions.akka,
        "com.playtech.mws.akka" %% "akka-cluster"            % Versions.akka,
        "com.playtech.mws.akka" %% "akka-cluster-sharding"   % Versions.akka,
        "com.playtech.mws.akka" %% "akka-cluster-tools"      % Versions.akka,
        "com.playtech.mws.akka" %% "akka-distributed-data"   % Versions.akka,
        "com.playtech.mws.akka" %% "akka-multi-node-testkit" % Versions.akka,
        "com.playtech.mws.akka" %% "akka-protobuf"           % Versions.akka,
        "com.playtech.mws.akka" %% "akka-remote"             % Versions.akka,
        "com.playtech.mws.akka" %% "akka-slf4j"              % Versions.akka,
        "com.playtech.mws.akka" %% "akka-stream"             % Versions.akka,
        "com.playtech.mws.akka" %% "akka-stream-testkit"     % Versions.akka,
        "com.playtech.mws.akka" %% "akka-testkit"            % Versions.akka
      )
    )
  )

  lazy val buildSettings = Seq(
    organization := "com.playtech.mws",
    description := "Abstract Scala Types Key-Value Storage",
    version := org.eclipse.jgit.api.Git.open(file(".")).describe().call(),
    scalaVersion := Versions.scala
  )

  override lazy val settings = super.settings ++ buildSettings ++ resolverSettings ++ Seq(
    shellPrompt := (Project.extract(_).currentProject.id + " > "))

  lazy val resolverSettings = Seq(
    resolvers ++= Seq(
      Resolver.mavenLocal,
      "releases resolver" at "http://nexus.mobile.playtechgaming.com/nexus/content/repositories/releases"
    )
  )

  lazy val publishSettings = Seq(
    publishTo := Some("releases" at "http://nexus.mobile.playtechgaming.com/nexus/content/repositories/releases"),
    credentials += Credentials("Sonatype Nexus Repository Manager","nexus.mobile.playtechgaming.com","wpl-deployer","aG1reeshie"),
    publishArtifact := true,
    publishArtifact in Compile := true,
    publishArtifact in Test := false,
    publishMavenStyle := true,
    pomIncludeRepository := (_ => false),
    publishLocal <<= publishM2,
    isSnapshot := true
  )
}
