
enablePlugins(ShadingPlugin)
shadedModules += "com.github.alexarchambault" %% "argonaut-shapeless_6.2"
shadingRules += ShadingRule.moveUnder("argonaut", "foo.shaded")
validNamespaces += "foo"

libraryDependencies ++= Seq(
  "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M5",
  // directly depending on that one for it not to be shaded
  "org.scala-lang" % "scala-reflect" % scalaVersion.value
)

excludeDependencies += sbt.ExclusionRule("com.chuusai", "shapeless_2.11")

scalaVersion := "2.11.8"
organization := "io.get-coursier.test"
name := "shading-exclude-dependencies"
version := "0.1.0-SNAPSHOT"
