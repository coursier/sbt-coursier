scalaVersion := "2.11.8"

libraryDependencies += "org.apache.hadoop" % "hadoop-yarn-server-resourcemanager" % "2.7.1"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}