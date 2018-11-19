scalaVersion := "2.11.8"

// keeping the default cache policies here

libraryDependencies += "com.chuusai" %% "shapeless" % "2.3.2" from {
  "https://repo1.maven.org/maven2/com/chuusai/shapeless_2.11/2.3.242/shapeless_2.11-2.3.242.jar"
}


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}