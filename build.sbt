name := "omd"

scalaVersion := "2.11.5"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.3.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.3.9",
  "com.google.guava" % "guava" % "18.0",
  "org.scalatest" % "scalatest_2.11" % "2.2.1" % "test"
)
  