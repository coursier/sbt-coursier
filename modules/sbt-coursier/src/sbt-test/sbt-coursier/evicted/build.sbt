scalaVersion := "2.11.8"

libraryDependencies ++= {
  sys.props("sbt.log.noformat") = "true" // disables colors in coursierWhatDependsOn output
  Seq(
    "org.typelevel" %% "cats-effect" % "1.3.1",
    "org.typelevel" %% "cats-core" % "1.5.0"
  )
}
