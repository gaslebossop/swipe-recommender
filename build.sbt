ThisBuild / scalaVersion := "3.3.4"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "twitninf"

val http4sVersion = "0.23.27"
val circeVersion  = "0.14.9"

lazy val root = (project in file("."))
  .settings(
    name := "swipe-recommender",
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-server" % http4sVersion,
      "org.http4s"     %% "http4s-dsl"          % http4sVersion,
      "org.http4s"     %% "http4s-circe"        % http4sVersion,
      "io.circe"       %% "circe-core"          % circeVersion,
      "io.circe"       %% "circe-parser"        % circeVersion,
      "org.postgresql" %  "postgresql"          % "42.7.4",
      "redis.clients"  %  "jedis"               % "5.2.0",
      "ch.qos.logback" %  "logback-classic"     % "1.5.6",
      "org.scalameta"  %% "munit"               % "1.0.2" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / mainClass := Some("swipe.Main"),
    assembly / mainClass := Some("swipe.Main"),
    assembly / assemblyJarName := "swipe-recommender.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class"           => MergeStrategy.discard
      case "reference.conf"              => MergeStrategy.concat
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )
