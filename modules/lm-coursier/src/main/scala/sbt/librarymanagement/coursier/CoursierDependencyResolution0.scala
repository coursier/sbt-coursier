package sbt.librarymanagement.coursier

import _root_.coursier.{Project, organizationString}
import _root_.coursier.sbtcoursier.{ArtifactsParams, ArtifactsRun, ResolutionParams, ResolutionRun, UpdateParams, UpdateRun}
import _root_.coursier.Cache
import sbt.librarymanagement._
import sbt.util.Logger

class CoursierDependencyResolution0 extends DependencyResolutionInterface {

  def moduleDescriptor(moduleSetting: ModuleDescriptorConfiguration): ModuleDescriptor =
    ???

  def update(
    module: ModuleDescriptor,
    configuration: UpdateConfiguration,
    uwconfig: UnresolvedWarningConfiguration,
    log: Logger
  ): Either[UnresolvedWarning, UpdateReport] = {

    val verbosityLevel = 0

    val parallelDownloads = 6
    val ttl = ???
    val createLogger = () => ???
    val cache = Cache.default
    val cachePolicies = ???
    val checksums: Seq[Option[String]] = ???
    val projectName = ???

    val project: Project = ???

    val resolutionParams = ResolutionParams(
      currentProject = project,
      fallbackDependencies = Nil,
      configGraphs = ???,
      autoScalaLib = true,
      mainRepositories = ???,
      parentProjectCache = Map.empty,
      interProjectDependencies = ???,
      internalRepositories = ???,
      userEnabledProfiles = Set.empty,
      userForceVersions = Map.empty,
      typelevel = false,
      so = org"org.scala-lang",
      sv = ???,
      sbtClassifiers = false,
      parallelDownloads = parallelDownloads,
      projectName = projectName,
      maxIterations = 200,
      createLogger = createLogger,
      cache = cache,
      cachePolicies = cachePolicies,
      ttl = ttl,
      checksums = checksums
    )

    val resolutions = ResolutionRun.resolutions(resolutionParams, verbosityLevel, log)

    val artifactsParams = ArtifactsParams(
      classifiers = None,
      res = resolutions.values.toSeq,
      includeSignatures = false,
      parallelDownloads = parallelDownloads,
      createLogger = createLogger,
      cache = cache,
      artifactsChecksums = checksums,
      ttl = ttl,
      cachePolicies = cachePolicies,
      projectName = projectName,
      sbtClassifiers = false
    )

    val artifacts = ArtifactsRun.artifacts(artifactsParams, verbosityLevel, log)

    val updateParams = UpdateParams(
      shadedConfigOpt = None,
      artifacts = artifacts,
      classifiers = None,
      configs = ???,
      currentProject = project,
      res = resolutions,
      ignoreArtifactErrors = false,
      includeSignatures = false,
      sbtBootJarOverrides = ???
    )

    val updateReport = UpdateRun.update(updateParams, verbosityLevel, log)

    Right(updateReport)
  }

}
