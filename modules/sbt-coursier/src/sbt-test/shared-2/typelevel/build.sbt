scalaOrganization := "org.typelevel"
scalaVersion := "2.11.7"
scalacOptions += "-Xexperimental"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}