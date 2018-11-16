scalaVersion := "2.11.8"

libraryDependencies += "ccl.northwestern.edu" % "netlogo" % "5.3.1" % "provided" from s"https://github.com/NetLogo/NetLogo/releases/download/5.3.1/NetLogo.jar"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}