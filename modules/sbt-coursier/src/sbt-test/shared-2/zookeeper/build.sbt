scalaVersion := "2.11.8"

libraryDependencies += "org.apache.zookeeper" % "zookeeper" % "3.5.0-alpha"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}