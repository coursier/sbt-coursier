scalaVersion := "2.12.1"
scalacOptions += "-Xfatal-warnings" // required for the test

enablePlugins(ScalaUnidocPlugin)
autoAPIMappings := true


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}