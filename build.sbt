val AppVersion = "0.1.0"
val AkkaVersion = "2.6.10"
val AkkaHttpVersion = "10.2.1"

val Env = sys.env.get("env").getOrElse("test")
val ImageVersion = if (Env == "test") s"test-$AppVersion" else AppVersion

val ServiceName = "messages-service"

name := ServiceName
lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      version := AppVersion,
      organization := "cn.freeriver",
      scalaVersion := "2.13.3"
    )),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
      "com.typesafe.akka" %% "akka-remote" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-persistence-cassandra" % "1.0.1",
      "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
      "com.lightbend.akka" %% "akka-projection-eventsourced" % "1.0.0",
      "com.lightbend.akka" %% "akka-projection-cassandra" % "1.0.0",
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "javax.ws.rs" % "javax.ws.rs-api" % "2.0.1",
      "com.github.swagger-akka-http" %% "swagger-akka-http" % "2.2.0",
      "co.pragmati" %% "swagger-ui-akka-http" % "1.4.0"
    ),
    packageName in Docker := s"colinzeng/$ServiceName",
    version in Docker := ImageVersion,
    dockerBaseImage := "colinzeng/openjdk-with-tools:8u265",
    dockerExposedPorts ++= Seq(80),
    daemonUserUid in Docker := None,
    daemonUser in Docker := "root",
    aggregate in Docker := false
  ).enablePlugins(JavaServerAppPackaging)