
scalaVersion := "2.12.2-bin-typelevel-4"
scalaOrganization := "org.typelevel"
scalacOptions += "-Yinduction-heuristics"

libraryDependencies ++= Seq(
  "com.47deg" %% "freestyle" % "0.1.0",
  compilerPlugin("org.scalamacros" %% "paradise" % "2.1.0" cross CrossVersion.patch)
)


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}