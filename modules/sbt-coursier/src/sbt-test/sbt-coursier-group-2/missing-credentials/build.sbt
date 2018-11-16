scalaVersion := "2.11.8"
credentials += Credentials(file("nope/nope/nope/nope/nope"))


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}