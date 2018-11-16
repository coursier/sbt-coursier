
lazy val noJbossInterceptorCheck = TaskKey[Unit]("noJbossInterceptorCheck")

noJbossInterceptorCheck := {

  val log = streams.value.log

  val configReport = updateSbtClassifiers.value
    .configuration(Default)
    .getOrElse {
      throw new Exception(
        "compile configuration not found in update report"
      )
    }

  val artifacts = configReport
    .modules
    .flatMap(_.artifacts)
    .map(_._1)

  val jbossInterceptorArtifacts = artifacts
    .filter { a =>
      a.name.contains("jboss-interceptor")
    }

  for (a <- jbossInterceptorArtifacts)
    log.error(s"Found jboss-interceptor artifact $a")

  assert(jbossInterceptorArtifacts.isEmpty)
}


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}