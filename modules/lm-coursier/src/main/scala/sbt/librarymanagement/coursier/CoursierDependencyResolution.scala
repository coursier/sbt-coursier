package sbt.librarymanagement.coursier

import java.io.OutputStreamWriter

import _root_.coursier.{CachePolicy, Organization, Project, TermDisplay, organizationString}
import _root_.coursier.ivy.IvyRepository
import _root_.coursier.sbtcoursier.{ArtifactsParams, ArtifactsRun, FromSbt, Inputs, InterProjectRepository, ResolutionParams, ResolutionRun, SbtBootJars, UpdateParams, UpdateRun}
import _root_.coursier.Cache
import sbt.internal.librarymanagement.IvySbt
import sbt.librarymanagement._
import sbt.util.Logger

class CoursierDependencyResolution(conf: CoursierConfiguration) extends DependencyResolutionInterface {

  private def sbtBinaryVersion = "1.0"

  def moduleDescriptor(moduleSetting: ModuleDescriptorConfiguration): CoursierModuleDescriptor =
    CoursierModuleDescriptor(moduleSetting, conf)

  def update(
    module: ModuleDescriptor,
    configuration: UpdateConfiguration,
    uwconfig: UnresolvedWarningConfiguration,
    log: Logger
  ): Either[UnresolvedWarning, UpdateReport] = {

    // TODO Take stuff in configuration into account? uwconfig too?

    val module0 = module match {
      case c: CoursierModuleDescriptor =>
        // seems not to happen, not sure what DependencyResolutionInterface.moduleDescriptor is for
        c.descriptor
      case i: IvySbt#Module =>
        i.moduleSettings match {
          case d: ModuleDescriptorConfiguration => d
          case other => sys.error(s"unrecognized module settings: $other")
        }
      case _ =>
        sys.error(s"unrecognized ModuleDescriptor type: $module")
    }

    val so = module0.scalaModuleInfo.fold(org"org.scala-lang")(m => Organization(m.scalaOrganization))
    val sv = module0.scalaModuleInfo.map(_.scalaFullVersion)
      // FIXME Manage to do stuff below without a scala version?
      .getOrElse(scala.util.Properties.versionNumberString)

    val sbv = module0.scalaModuleInfo.map(_.scalaBinaryVersion).getOrElse {
      sv.split('.').take(2).mkString(".")
    }

    val verbosityLevel = 0

    val ttl = Cache.defaultTtl
    val createLogger = { () =>
      new TermDisplay(new OutputStreamWriter(System.err), fallbackMode = true)
    }
    val cache = Cache.default
    val cachePolicies = CachePolicy.default
    val checksums = Cache.defaultChecksums
    val projectName = "" // used for logging only…

    val ivyProperties = ResolutionParams.defaultIvyProperties()

    val resolvers =
      if (conf.reorderResolvers)
        ResolutionParams.reorderResolvers(conf.resolvers)
      else
        conf.resolvers

    val mainRepositories = resolvers
      .flatMap { resolver =>
        FromSbt.repository(
          resolver,
          ivyProperties,
          log,
          None // FIXME What about authentication?
        )
      }

    val globalPluginsRepos =
      for (p <- ResolutionParams.globalPluginPatterns(sbtBinaryVersion))
        yield IvyRepository.fromPattern(
          p,
          withChecksums = false,
          withSignatures = false,
          withArtifacts = false
        )

    val interProjectDependencies: Seq[Project] = Nil // TODO Don't use Nil here
    val interProjectRepo = InterProjectRepository(interProjectDependencies)

    val internalRepositories = globalPluginsRepos :+ interProjectRepo

    val dependencies = module0.dependencies.flatMap { d =>
      // crossVersion already taken into account, wiping it here
      val d0 = d.withCrossVersion(CrossVersion.Disabled())
      FromSbt.dependencies(d0, sv, sbv)
    }

    val configGraphs = Inputs.ivyGraphs(
      Inputs.configExtends(module0.configurations)
    )

    val resolutionParams = ResolutionParams(
      dependencies = dependencies,
      fallbackDependencies = Nil,
      configGraphs = configGraphs,
      autoScalaLib = true,
      mainRepositories = mainRepositories,
      parentProjectCache = Map.empty,
      interProjectDependencies = interProjectDependencies,
      internalRepositories = internalRepositories,
      userEnabledProfiles = Set.empty,
      userForceVersions = Map.empty,
      typelevel = false,
      so = so,
      sv = sv,
      sbtClassifiers = false,
      parallelDownloads = conf.parallelDownloads,
      projectName = projectName,
      maxIterations = conf.maxIterations,
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
      parallelDownloads = conf.parallelDownloads,
      createLogger = createLogger,
      cache = cache,
      artifactsChecksums = checksums,
      ttl = ttl,
      cachePolicies = cachePolicies,
      projectName = projectName,
      sbtClassifiers = false
    )

    val artifacts = ArtifactsRun.artifacts(artifactsParams, verbosityLevel, log)

    val configs = Inputs.coursierConfigurations(module0.configurations)

    val sbtBootJarOverrides = SbtBootJars(
      conf.sbtScalaOrganization.fold(org"org.scala-lang")(Organization(_)),
      conf.sbtScalaVersion.getOrElse(sv),
      conf.sbtScalaJars
    )

    val updateParams = UpdateParams(
      shadedConfigOpt = None,
      artifacts = artifacts,
      classifiers = None,
      configs = configs,
      dependencies = dependencies,
      res = resolutions,
      ignoreArtifactErrors = false,
      includeSignatures = false,
      sbtBootJarOverrides = sbtBootJarOverrides
    )

    val updateReport = UpdateRun.update(updateParams, verbosityLevel, log)

    Right(updateReport)
  }

}

object CoursierDependencyResolution {
  def apply(configuration: CoursierConfiguration): DependencyResolution =
    DependencyResolution(new CoursierDependencyResolution(configuration))
}
