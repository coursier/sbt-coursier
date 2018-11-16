
scalaVersion := "2.11.8"

organization := "io.get-coursier.test"
name := "sbt-coursier-exclude-dependencies"
version := "0.1.0-SNAPSHOT"

libraryDependencies += "com.github.alexarchambault" %% "argonaut-shapeless_6.1" % "1.0.0-RC1"

excludeDependencies += sbt.ExclusionRule("com.chuusai", "shapeless_2.11")
excludeDependencies += "io.argonaut" %% "argonaut"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}