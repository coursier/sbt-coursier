scalaVersion := "2.11.8"
libraryDependencies += "org.jclouds.api" % "nova" % "1.5.9" classifier "tests"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}