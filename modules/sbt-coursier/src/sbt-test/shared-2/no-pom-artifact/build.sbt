
lazy val noPomCheck = TaskKey[Unit]("noPomCheck")

noPomCheck := {

  val log = streams.value.log

  val configReport = update.value
    .configuration(Compile)
    .getOrElse {
      throw new Exception(
        "compile configuration not found in update report"
      )
    }

  val artifacts = configReport
    .modules
    .flatMap(_.artifacts)
    .map(_._1)

  val pomArtifacts = artifacts
    .filter { a =>
      a.`type` == "pom" && a.classifier.isEmpty
    }

  for (a <- pomArtifacts)
    log.error(s"Found POM artifact $a")

  assert(pomArtifacts.isEmpty)
}


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}