package coursier.lmcoursier

import java.io.File

import _root_.coursier.{Artifact, Organization, Resolution, organizationString}
import _root_.coursier.core.{Classifier, Configuration, ModuleName}
import _root_.coursier.lmcoursier.Inputs.withAuthenticationByHost
import coursier.cache.{CacheDefaults, CachePolicy, FileCache}
import coursier.internal.Typelevel
import sbt.internal.librarymanagement.IvySbt
import sbt.librarymanagement._
import sbt.util.Logger

class CoursierDependencyResolution(conf: CoursierConfiguration) extends DependencyResolutionInterface {

  /*
   * Based on earlier implementations by @leonardehrenfried (https://github.com/sbt/librarymanagement/pull/190)
   * and @andreaTP (https://github.com/sbt/librarymanagement/pull/270), then adapted to the code from the former
   * sbt-coursier, that was moved to this module.
   */

  lazy val resolvers =
    if (conf.reorderResolvers)
      ResolutionParams.reorderResolvers(conf.resolvers)
    else
      conf.resolvers

  private lazy val excludeDependencies = conf
    .excludeDependencies
    .map {
      case (strOrg, strName) =>
        (Organization(strOrg), ModuleName(strName))
    }
    .toSet

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

    val so = conf.scalaOrganization.map(Organization(_))
      .orElse(module0.scalaModuleInfo.map(m => Organization(m.scalaOrganization)))
      .getOrElse(org"org.scala-lang")
    val sv = conf.scalaVersion
      .orElse(module0.scalaModuleInfo.map(_.scalaFullVersion))
      // FIXME Manage to do stuff below without a scala version?
      .getOrElse(scala.util.Properties.versionNumberString)

    val sbv = module0.scalaModuleInfo.map(_.scalaBinaryVersion).getOrElse {
      sv.split('.').take(2).mkString(".")
    }

    val verbosityLevel = 0

    val ttl = CacheDefaults.ttl
    val loggerOpt = conf.logger
    val cache = conf.cache.getOrElse(CacheDefaults.location)
    val cachePolicies = CachePolicy.default
    val checksums = CacheDefaults.checksums
    val projectName = "" // used for logging only…

    val ivyProperties = ResolutionParams.defaultIvyProperties()

    val classifiers =
      if (conf.hasClassifiers)
        Some(conf.classifiers.map(Classifier(_)))
      else
        None

    val authenticationByRepositoryId = conf.authenticationByRepositoryId.toMap

    val mainRepositories = resolvers
      .flatMap { resolver =>
        FromSbt.repository(
          resolver,
          ivyProperties,
          log,
          authenticationByRepositoryId.get(resolver.name)
        )
      }
      .map(withAuthenticationByHost(_, conf.authenticationByHost.toMap))

    val interProjectRepo = InterProjectRepository(conf.interProjectDependencies)

    val dependencies = module0
      .dependencies
      .flatMap { d =>
        // crossVersion sometimes already taken into account (when called via the update task), sometimes not
        // (e.g. sbt-dotty 0.13.0-RC1)
        FromSbt.dependencies(d, sv, sbv, optionalCrossVer = true)
      }
      .map {
        case (config, dep) =>
          val dep0 = dep.copy(
            exclusions = dep.exclusions ++ excludeDependencies
          )
          (config, dep0)
      }

    val configGraphs = Inputs.ivyGraphs(
      Inputs.configExtends(module0.configurations)
    )

    val typelevel = so == Typelevel.typelevelOrg

    val resolutionParams = ResolutionParams(
      dependencies = dependencies,
      fallbackDependencies = conf.fallbackDependencies,
      configGraphs = configGraphs,
      autoScalaLibOpt = if (conf.autoScalaLibrary) Some((so, sv)) else None,
      mainRepositories = mainRepositories,
      parentProjectCache = Map.empty,
      interProjectDependencies = conf.interProjectDependencies,
      internalRepositories = Seq(interProjectRepo),
      sbtClassifiers = false,
      projectName = projectName,
      loggerOpt = loggerOpt,
      cache = coursier.cache.FileCache()
        .withLocation(cache)
        .withCachePolicies(cachePolicies)
        .withTtl(ttl)
        .withChecksums(checksums),
      parallel = conf.parallelDownloads,
      params = coursier.params.ResolutionParams()
        .withMaxIterations(conf.maxIterations)
        .withProfiles(conf.mavenProfiles.toSet)
        .withForceVersion(Map.empty)
        .withTypelevel(typelevel)
    )

    def artifactsParams(resolutions: Map[Set[Configuration], Resolution]) =
      ArtifactsParams(
        classifiers = classifiers,
        resolutions = resolutions.values.toSeq,
        includeSignatures = false,
        loggerOpt = loggerOpt,
        projectName = projectName,
        sbtClassifiers = false,
        cache = FileCache()
          .withLocation(cache)
          .withChecksums(checksums)
          .withTtl(ttl)
          .withCachePolicies(cachePolicies),
        parallel = conf.parallelDownloads
      )

    val sbtBootJarOverrides = SbtBootJars(
      conf.sbtScalaOrganization.fold(org"org.scala-lang")(Organization(_)),
      conf.sbtScalaVersion.getOrElse(sv),
      conf.sbtScalaJars
    )

    val configs = Inputs.coursierConfigurations(module0.configurations)

    def updateParams(
      resolutions: Map[Set[Configuration], Resolution],
      artifacts: Map[Artifact, File]
    ) =
      UpdateParams(
        shadedConfigOpt = None,
        artifacts = artifacts,
        classifiers = classifiers,
        configs = configs,
        dependencies = dependencies,
        res = resolutions,
        includeSignatures = false,
        sbtBootJarOverrides = sbtBootJarOverrides
      )

    val e = for {
      resolutions <- ResolutionRun.resolutions(resolutionParams, verbosityLevel, log)
      artifactsParams0 = artifactsParams(resolutions)
      artifacts <- ArtifactsRun.artifacts(artifactsParams0, verbosityLevel, log)
    } yield {
      val updateParams0 = updateParams(resolutions, artifacts)
      UpdateRun.update(updateParams0, verbosityLevel, log)
    }

    e.left.map(unresolvedWarningOrThrow(uwconfig, _))
  }

  private def unresolvedWarningOrThrow(
    uwconfig: UnresolvedWarningConfiguration,
    ex: coursier.error.CoursierError
  ): UnresolvedWarning = {

    // TODO Take coursier.error.FetchError.DownloadingArtifacts into account

    val downloadErrors = ex match {
      case ex0: coursier.error.ResolutionError =>
        ex0.errors.collect {
          case err: coursier.error.ResolutionError.CantDownloadModule => err
        }
      case _ =>
        Nil
    }
    val otherErrors = ex match {
      case ex0: coursier.error.ResolutionError =>
        ex0.errors.flatMap {
          case _: coursier.error.ResolutionError.CantDownloadModule => None
          case err => Some(err)
        }
      case _ =>
        Seq(ex)
    }

    if (otherErrors.isEmpty) {
        val r = new ResolveException(
          downloadErrors.map(_.getMessage),
          downloadErrors.map { err =>
            ModuleID(err.module.organization.value, err.module.name.value, err.version)
              .withExtraAttributes(err.module.attributes)
          }
        )
        UnresolvedWarning(r, uwconfig)
    } else
      throw ex
  }

}

object CoursierDependencyResolution {
  def apply(configuration: CoursierConfiguration): DependencyResolution =
    DependencyResolution(new CoursierDependencyResolution(configuration))
}
