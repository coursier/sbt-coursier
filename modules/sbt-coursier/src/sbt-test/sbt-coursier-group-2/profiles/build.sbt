scalaVersion := "2.11.8"

libraryDependencies += "org.apache.spark" %% "spark-sql" % "1.6.2"

mavenProfiles += "hadoop-2.6"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}