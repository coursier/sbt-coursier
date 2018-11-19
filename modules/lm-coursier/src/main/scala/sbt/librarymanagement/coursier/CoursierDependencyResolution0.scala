package sbt.librarymanagement.coursier

import java.io.{File, OutputStreamWriter}
import java.util.concurrent.Executors

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import coursier.{Artifact, Resolution, _}
import coursier.core.{Classifier, Configuration, Type}
import coursier.sbtcoursier.{FromSbt, ToSbt}
import coursier.util.{Gather, Task}
import sbt.internal.librarymanagement.IvySbt
import sbt.librarymanagement.Configurations.{CompilerPlugin, Component, ScalaTool}
import sbt.librarymanagement.{Configuration => _, _}
import sbt.util.Logger
import sjsonnew.support.murmurhash.Hasher

private[sbt] class CoursierDependencyResolution0(coursierConfiguration: CoursierConfiguration)
    extends DependencyResolutionInterface {

  // keep the pool alive while the class is loaded
  private lazy val pool =
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(coursierConfiguration.parallelDownloads)
    )

  private[coursier] val reorderedResolvers = {
    val resolvers0 =
      coursierConfiguration.resolvers ++ coursierConfiguration.otherResolvers

    if (coursierConfiguration.reorderResolvers) {
      Resolvers.reorder(resolvers0)
    } else resolvers0
  }

  def extraInputHash: Long = {
    import CustomLibraryManagementCodec._
    Hasher.hash(coursierConfiguration) match {
      case Success(keyHash) => keyHash.toLong
      case Failure(_)       => 0L
    }
  }

  /**
   * Builds a ModuleDescriptor that describes a subproject with dependencies.
   *
   * @param moduleSetting It contains the information about the module including the dependencies.
   * @return A `ModuleDescriptor` describing a subproject and its dependencies.
   */
  override def moduleDescriptor(
      moduleSetting: ModuleDescriptorConfiguration): CoursierModuleDescriptor =
    CoursierModuleDescriptor(moduleSetting, coursierConfiguration)

  val ivyHome = sys.props.getOrElse(
    "ivy.home",
    new File(sys.props("user.home")).toURI.getPath + ".ivy2"
  )

  val sbtIvyHome = sys.props.getOrElse(
    "sbt.ivy.home",
    ivyHome
  )

  val ivyProperties = Map(
    "ivy.home" -> ivyHome,
    "sbt.ivy.home" -> sbtIvyHome
  ) ++ sys.props

  /**
   * Resolves the given module's dependencies performing a retrieval.
   *
   * @param module        The module to be resolved.
   * @param configuration The update configuration.
   * @param uwconfig      The configuration to handle unresolved warnings.
   * @param log           The logger.
   * @return The result, either an unresolved warning or an update report. Note that this
   *         update report will or will not be successful depending on the `missingOk` option.
   */
  override def update(module: ModuleDescriptor,
                      configuration: UpdateConfiguration,
                      uwconfig: UnresolvedWarningConfiguration,
                      log: Logger): Either[UnresolvedWarning, UpdateReport] = {

    // not sure what DependencyResolutionInterface.moduleDescriptor is for, we're handled ivy stuff anyway...
    val module0 = module match {
      case c: CoursierModuleDescriptor => c
      // This shouldn't happen at best of my understanding
      case i: IvySbt#Module =>
        moduleDescriptor(
          i.moduleSettings match {
            case c: ModuleDescriptorConfiguration => c
            case other                            => sys.error(s"unrecognized module settings: $other")
          }
        )
      case _ =>
        sys.error(s"unrecognized ModuleDescriptor type: $module")
    }

    if (reorderedResolvers.isEmpty) {
      log.error(
        "Dependency resolution is configured with an empty list of resolvers. This is unlikely to work.")
    }

    val dependencies = module.directDependencies.flatMap(toCoursierDependency).toSet
    val start = Resolution(dependencies.map(_._1))
    val authentication = None // TODO: get correct value
    val ivyConfiguration = ivyProperties // TODO: is it enough?

    val repositories =
      reorderedResolvers.flatMap(r => FromSbt.repository(r, ivyConfiguration, log, authentication)) ++ Seq(
        Cache.ivy2Local,
        Cache.ivy2Cache
      )

    implicit val ec = pool

    val coursierLogger = createLogger()
    try {
      val fetch = Fetch.from(
        repositories,
        Cache.fetch[Task](logger = Some(coursierLogger))
      )
      val resolution = start.process
        .run(fetch, coursierConfiguration.maxIterations)
        .unsafeRun()

      def updateReport() = {
        val localArtifacts: Map[Artifact, Either[FileError, File]] = Gather[Task]
          .gather(
            resolution.artifacts().map { a =>
              Cache
                .file[Task](a, logger = Some(coursierLogger))
                .run
                .map((a, _))
            }
          )
          .unsafeRun()
          .toMap

        toUpdateReport(resolution,
                       (module0.descriptor.configurations.map(c => Configuration(c.name)) ++ dependencies.map(_._2)).distinct,
                       localArtifacts,
                       log)
      }

      if (resolution.isDone &&
          resolution.errors.isEmpty &&
          resolution.conflicts.isEmpty) {
        updateReport()
      } else if (resolution.isDone &&
                 (resolution.errors.nonEmpty && configuration.missingOk)
                 && resolution.conflicts.isEmpty) {
        log.warn(s"""Failed to download artifacts: ${resolution.errors
          .flatMap(_._2)
          .mkString(", ")}""")
        updateReport()
      } else {
        toSbtError(log, uwconfig, resolution)
      }
    } finally {
      coursierLogger.stop()
    }
  }

  // utilities
  private def createLogger() = {
    val t = new TermDisplay(new OutputStreamWriter(System.out))
    t.init()
    t
  }

  private def toCoursierDependency(moduleID: ModuleID) = {
    val attributes =
      if (moduleID.explicitArtifacts.isEmpty)
        Seq(Attributes(Type.empty, Classifier.empty))
      else
        moduleID.explicitArtifacts.map { a =>
          Attributes(`type` = Type(a.`type`), classifier = a.classifier.fold(Classifier.empty)(Classifier(_)))
        }

    val extraAttrs = FromSbt.attributes(moduleID.extraDependencyAttributes)

    val mapping = moduleID.configurations.getOrElse("compile")

    import _root_.coursier.ivy.IvyXml.{ mappings => ivyXmlMappings }
    val allMappings = ivyXmlMappings(mapping)

    for {
      (from, to) <- allMappings
      attr <- attributes
    } yield {
      Dependency(
        Module(Organization(moduleID.organization), ModuleName(moduleID.name), extraAttrs),
        moduleID.revision,
        configuration = to,
        attributes = attr,
        exclusions = moduleID.exclusions.map { rule =>
          (Organization(rule.organization), ModuleName(rule.name))
        }.toSet,
        transitive = moduleID.isTransitive
      ) -> from
    }
  }

  private def toUpdateReport(resolution: Resolution,
                             configurations: Seq[Configuration],
                             artifactFilesOrErrors0: Map[Artifact, Either[FileError, File]],
                             log: Logger): Either[UnresolvedWarning, UpdateReport] = {

    val artifactFiles = artifactFilesOrErrors0.collect {
      case (artifact, Right(file)) =>
        artifact -> file
    }

    val artifactErrors = artifactFilesOrErrors0.toVector
      .collect {
        case (a, Left(err)) if !a.optional || !err.notFound =>
          a -> err
      }

    val erroredArtifacts = artifactFilesOrErrors0.collect {
      case (a, Left(_)) => a
    }.toSet

    val depsByConfig = {
      val deps = resolution.dependencies.toVector
      configurations
        .map((_, deps))
        .toMap
    }

    val configurations0 = extractConfigurationTree(configurations)

    val configResolutions =
      (depsByConfig.keys ++ configurations0.keys).map(k => (k, resolution)).toMap

    val sbtBootJarOverrides = Map.empty[(Module, String), File]
    val classifiers = None // TODO: get correct values

    if (artifactErrors.isEmpty) {
      Right(
        ToSbt.updateReport(
          depsByConfig,
          configResolutions,
          configurations0,
          classifiers,
          artifactFileOpt(
            sbtBootJarOverrides,
            artifactFiles,
            erroredArtifacts,
            log,
            _,
            _,
            _,
            _
          ),
          log
        ))
    } else {
      throw new RuntimeException(s"Could not save downloaded dependencies: $erroredArtifacts")
    }

  }

  type ConfigurationDependencyTree = Map[Configuration, Set[Configuration]]

  // Key is the name of the configuration (i.e. `compile`) and the values are the name itself plus the
  // names of the configurations that this one depends on.
  private def extractConfigurationTree(available: Seq[Configuration]): ConfigurationDependencyTree =
    (Configurations.default ++
      Configurations.defaultInternal ++
      Seq(ScalaTool, CompilerPlugin, Component))
      .filter(c => available.contains(Configuration(c.name)))
      .map(c => (Configuration(c.name), c.extendsConfigs.map(c => Configuration(c.name)) :+ Configuration(c.name)))
      .toMap
      .mapValues(_.toSet)

  private def artifactFileOpt(
      sbtBootJarOverrides: Map[(Module, String), File],
      artifactFiles: Map[Artifact, File],
      erroredArtifacts: Set[Artifact],
      log: Logger,
      module: Module,
      version: String,
      attr: Attributes,
      artifact: Artifact
  ) = {

    val artifact0 = artifact

    // Under some conditions, SBT puts the scala JARs of its own classpath
    // in the application classpath. Ensuring we return SBT's jars rather than
    // JARs from the coursier cache, so that a same JAR doesn't land twice in the
    // application classpath (once via SBT jars, once via coursier cache).
    val fromBootJars =
      if (attr.classifier.isEmpty && attr.`type` == Type.jar)
        sbtBootJarOverrides.get((module, version))
      else
        None

    val res = fromBootJars.orElse(artifactFiles.get(artifact0))
    if (res.isEmpty && !erroredArtifacts(artifact0))
      log.error(s"${artifact.url} not downloaded (should not happen)")

    res
  }

  private def toSbtError(log: Logger,
                         uwconfig: UnresolvedWarningConfiguration,
                         resolution: Resolution) = {
    val failedResolution = resolution.errors.map {
      case ((failedModule, failedVersion), _) =>
        ModuleID(failedModule.organization.value, failedModule.name.value, failedVersion)
    }
    val msgs = resolution.errors.flatMap(_._2)
    log.debug(s"Failed resolution: $msgs")
    log.debug(s"Missing artifacts: $failedResolution")
    val ex = new ResolveException(msgs, failedResolution)
    Left(UnresolvedWarning(ex, uwconfig))
  }
}
