
val org = "io.get-coursier.tests"
val nme = "coursier-test-a"
val ver = "0.1-SNAPSHOT"

lazy val a = project
  .settings(
    organization := org,
    name := nme,
    publishArtifact.in(Test) := true,
    version := ver
  )

lazy val b = project
  .settings(
    classpathTypes += "test-jar",
    libraryDependencies ++= Seq(
      org %% nme % ver,
      org %% nme % ver % "test" classifier "tests"
    )
  )



{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}