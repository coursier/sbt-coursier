package coursier.lmcoursier

import java.io.{File, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import _root_.coursier.{Artifact, Cache, CachePolicy, FileError, Organization, Resolution, TermDisplay, organizationString}
import _root_.coursier.core.{Classifier, Configuration, ModuleName, Type}
import _root_.coursier.extra.Typelevel
import _root_.coursier.ivy.IvyRepository
import _root_.coursier.lmcoursier.Inputs.withAuthenticationByHost
import coursier.maven.{MavenAttributes, Pom}
import sbt.hack.ScalaArtifactsThing
import sbt.internal.librarymanagement.IvySbt
import sbt.librarymanagement._
import sbt.util.Logger

class CoursierDependencyResolution(conf: CoursierConfiguration) extends DependencyResolutionInterface {

  private def sbtBinaryVersion = "1.0"

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
          case c: PomConfiguration =>

            // should we use the default charset here?
            val s = new String(Files.readAllBytes(c.file.toPath), StandardCharsets.UTF_8)

            val node = coursier.core.compatibility.xmlParse(s) match {
              case Left(e) =>
                throw new Exception(s"Error parsing POM file ${c.file}: $e")
              case Right(n) => n
            }

            val deps = Pom.project(node) match {
              case Left(e) =>
                throw new Exception(s"Error parsing POM file ${c.file}: $e")
              case Right(p) =>
                p.dependencies
            }

            // FIXME Here, we convert things to librarymanagement classes, before converting them back to coursier ones…

            val deps0 = deps.toVector.map {
              case (conf, dep) =>
                val conf0 = if (conf.isEmpty) Configuration.compile else conf
                val depConf = if (dep.configuration.isEmpty) Configuration.compile else dep.configuration
                val exclusions = dep.exclusions.toVector.sorted.map {
                  case (o, n) =>
                    InclExclRule(o.value, n.value)
                }
                val explicitArtifacts =
                  if (dep.attributes.isEmpty)
                    Vector()
                  else {
                    val tpe = Some(dep.attributes.`type`).filter(_.nonEmpty).getOrElse(Type.jar)
                    val art = sbt.librarymanagement.Artifact(dep.module.name.value)
                      .withType(tpe.value)
                      .withExtension(MavenAttributes.typeExtension(tpe).value)
                      .withClassifier(Some(dep.attributes.classifier).filter(_.nonEmpty).map(_.value))
                    Vector(art)
                  }
                ModuleID(dep.module.organization.value, dep.module.name.value, dep.version)
                  .withExtraAttributes(dep.module.attributes)
                  .withIsTransitive(dep.transitive)
                  .withConfigurations(Some(s"${conf0.value}->${depConf.value}"))
                  .withExclusions(exclusions)
                  .withExplicitArtifacts(explicitArtifacts)
            }

            // these are unused anyway
            val moduleId = ModuleID("dummy", "dummy" ,"dummy")
            val moduleInfo = ModuleInfo("dummy")

            val desc = ModuleDescriptorConfiguration(moduleId, moduleInfo)
              .withScalaModuleInfo(c.scalaModuleInfo)
              .withConfigurations(Configurations.default ++ Configurations.defaultInternal)
              .withDependencies(deps0)

            // seems sbt adds those too
            c.scalaModuleInfo.filter(_ => c.autoScalaTools) match {
              case Some(info) =>
                // FIXME What about the isDotty param of toolDependencies?
                desc
                  .withConfigurations(desc.configurations :+ Configurations.ScalaTool)
                  .withDependencies(desc.dependencies ++ ScalaArtifactsThing.toolDependencies(info.scalaOrganization, info.scalaFullVersion))
              case None =>
                desc
            }

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

    val ttl = Cache.defaultTtl
    val createLogger = conf.createLogger.map(_.create).getOrElse { () =>
      new TermDisplay(new OutputStreamWriter(System.err), fallbackMode = true)
    }
    val cache = conf.cache.getOrElse(Cache.default)
    val cachePolicies = CachePolicy.default
    val checksums = Cache.defaultChecksums
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

    val globalPluginsRepos =
      for (p <- ResolutionParams.globalPluginPatterns(sbtBinaryVersion))
        yield IvyRepository.fromPattern(
          p,
          withChecksums = false,
          withSignatures = false,
          withArtifacts = false
        )

    val interProjectRepo = InterProjectRepository(conf.interProjectDependencies)

    val internalRepositories = globalPluginsRepos :+ interProjectRepo

    val dependencies = module0
      .dependencies
      .flatMap { d =>
        // crossVersion already taken into account, wiping it here
        val d0 = d.withCrossVersion(CrossVersion.Disabled())
        FromSbt.dependencies(d0, sv, sbv)
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
      autoScalaLib = conf.autoScalaLibrary,
      mainRepositories = mainRepositories,
      parentProjectCache = Map.empty,
      interProjectDependencies = conf.interProjectDependencies,
      internalRepositories = internalRepositories,
      userEnabledProfiles = conf.mavenProfiles.toSet,
      userForceVersions = Map.empty,
      typelevel = typelevel,
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

    def artifactsParams(resolutions: Map[Set[Configuration], Resolution]) =
      ArtifactsParams(
        classifiers = classifiers,
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

    val sbtBootJarOverrides = SbtBootJars(
      conf.sbtScalaOrganization.fold(org"org.scala-lang")(Organization(_)),
      conf.sbtScalaVersion.getOrElse(sv),
      conf.sbtScalaJars
    )

    val configs = Inputs.coursierConfigurations(module0.configurations)

    def updateParams(
      resolutions: Map[Set[Configuration], Resolution],
      artifacts: Map[Artifact, Either[FileError, File]]
    ) =
      UpdateParams(
        shadedConfigOpt = None,
        artifacts = artifacts,
        classifiers = classifiers,
        configs = configs,
        dependencies = dependencies,
        res = resolutions,
        ignoreArtifactErrors = false,
        includeSignatures = false,
        sbtBootJarOverrides = sbtBootJarOverrides
      )

    val e = for {
      resolutions <- ResolutionRun.resolutions(resolutionParams, verbosityLevel, log)
      artifactsParams0 = artifactsParams(resolutions)
      artifacts <- ArtifactsRun.artifacts(artifactsParams0, verbosityLevel, log)
      updateParams0 = updateParams(resolutions, artifacts)
      updateReport <- UpdateRun.update(updateParams0, verbosityLevel, log)
    } yield updateReport

    e.left.map(unresolvedWarningOrThrow(uwconfig, _))
  }

  private def resolutionException(ex: ResolutionError): Either[Throwable, ResolveException] =
    ex match {
      case e: ResolutionError.MetadataDownloadErrors =>
        val r = new ResolveException(
          e.errors.flatMap(_._2),
          e.errors.map {
            case ((mod, ver), _) =>
              ModuleID(mod.organization.value, mod.name.value, ver)
                .withExtraAttributes(mod.attributes)
          }
        )
        Right(r)
      case _ => Left(ex.exception())
    }

  private def unresolvedWarningOrThrow(
    uwconfig: UnresolvedWarningConfiguration,
    ex: ResolutionError
  ): UnresolvedWarning =
    resolutionException(ex) match {
      case Left(t) => throw t
      case Right(e) =>
        UnresolvedWarning(e, uwconfig)
    }

}

object CoursierDependencyResolution {
  def apply(configuration: CoursierConfiguration): DependencyResolution =
    DependencyResolution(new CoursierDependencyResolution(configuration))
}
