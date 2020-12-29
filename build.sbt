val kvs = project.in(file(".")).settings(
  version := zero.git.version()
, scalaVersion := "2.13.4"
, resolvers += Resolver.jcenterRepo
, resolvers += Resolver.githubPackages("zero-deps")
, libraryDependencies ++= Seq(
    "org.rocksdb" % "rocksdbjni" % "6.14.6"
  , "org.lz4" % "lz4-java" % "1.7.1"
  , "org.apache.lucene" % "lucene-analyzers-common" % "8.4.1"
  , "dev.zio" %% "zio-nio"          % "1.0.0-RC9"
  , "dev.zio" %% "zio-macros"       % "1.0.3"
  , "dev.zio" %% "zio-test-sbt"     % "1.0.3" % Test
  , "com.typesafe.akka" %% "akka-cluster-sharding" % "2.6.10"
  , "io.github.zero-deps" %% "proto-macros"  % "1.8" % Compile
  , "io.github.zero-deps" %% "proto-runtime" % "1.8"
  , compilerPlugin(
    "io.github.zero-deps" %% "eq" % "2.5")
  , "io.github.zero-deps" %% "ext" % "2.4.2.g2a97c55"
  , compilerPlugin(
    "org.typelevel" %% "kind-projector" % "0.11.2" cross CrossVersion.full)
  )
, testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
, scalacOptions ++= opts
)

val examples = project.in(file("examples")).dependsOn(kvs).settings(
  cancelable in Global := true
, fork in run := true
, scalaVersion := "2.13.4"
)

val opts = Seq(
    "-deprecation"
  // , "-explaintypes"
  , "-feature"
  , "-language:_"
  , "-unchecked"
  , "-Xcheckinit"
  // , "-Xfatal-warnings"
  , "-Xlint:adapted-args"
  , "-Xlint:constant"
  , "-Xlint:delayedinit-select"
  , "-Xlint:inaccessible"
  , "-Xlint:infer-any"
  , "-Xlint:missing-interpolator"
  , "-Xlint:nullary-unit"
  , "-Xlint:option-implicit"
  , "-Xlint:package-object-classes"
  , "-Xlint:poly-implicit-overload"
  , "-Xlint:private-shadow"
  , "-Xlint:stars-align"
  , "-Xlint:type-parameter-shadow"
  , "-Ywarn-dead-code"
  , "-Ywarn-extra-implicit"
  , "-Ywarn-numeric-widen"
  , "-Ywarn-value-discard"
  , "-Ywarn-unused:implicits"
  , "-Ywarn-unused:imports"
  , "-Ywarn-unused:params"
  , "-encoding", "UTF-8"
  , "-Xmaxerrs", "1"
  , "-Xmaxwarns", "3"
  , "-Wconf:cat=deprecation&msg=Auto-application:silent"
  , "-Ymacro-annotations"
)

turbo := true
useCoursier := true
Global / onChangedBuildSource := ReloadOnSourceChanges
