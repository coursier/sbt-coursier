scalaVersion := "2.11.8"

coursierArtifacts := {
  val f = file("coursier-artifacts")
  if (f.exists())
    sys.error(s"$f file found")

  java.nio.file.Files.write(f.toPath, Array.empty[Byte])
  coursierArtifacts.value
}


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}