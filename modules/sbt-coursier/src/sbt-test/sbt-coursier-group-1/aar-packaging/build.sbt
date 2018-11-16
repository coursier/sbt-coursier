scalaVersion := "2.11.8"

libraryDependencies += ("com.rengwuxian.materialedittext" % "library" % "2.1.4")
  .exclude("com.android.support", "support-v4")
  .exclude("com.android.support", "support-annotations")
  .exclude("com.android.support", "appcompat-v7")


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}