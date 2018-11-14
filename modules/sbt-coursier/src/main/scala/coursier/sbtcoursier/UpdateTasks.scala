package coursier.sbtcoursier

import java.io.File

import coursier.{FileError, FromSbt, ToSbt}
import coursier.core.Resolution.ModuleVersion
import coursier.core._
import coursier.sbtcoursier.Keys._
import coursier.util.Print
import sbt.librarymanagement.{Artifact => _, Configuration => _, _}
import sbt.{Def, UpdateReport}
import sbt.Keys._
import sbt.util.Logger

object UpdateTasks {

  private def artifactFileOpt(
    sbtBootJarOverrides: Map[(Module, String), File],
    artifactFiles: Map[Artifact, File],
    erroredArtifacts: Set[Artifact]
  )(
    module: Module,
    version: String,
    attributes: Attributes,
    artifact: Artifact
  ): Option[File] = {

    // Under some conditions, SBT puts the scala JARs of its own classpath
    // in the application classpath. Ensuring we return SBT's jars rather than
    // JARs from the coursier cache, so that a same JAR doesn't land twice in the
    // application classpath (once via SBT jars, once via coursier cache).
    val fromBootJars =
      if (attributes.classifier.isEmpty && attributes.`type` == Type.jar)
        sbtBootJarOverrides.get((module, version))
      else
        None

    val res = fromBootJars.orElse(artifactFiles.get(artifact))

    if (res.isEmpty && !erroredArtifacts(artifact))
      sys.error(s"${artifact.url} not downloaded (should not happen)")

    res
  }

  // Move back to coursier.util (in core module) after 1.0?
  private def allDependenciesByConfig(
    res: Map[Configuration, Resolution],
    depsByConfig: Map[Configuration, Set[Dependency]],
    configs: Map[Configuration, Set[Configuration]]
  ): Map[Configuration, Set[Dependency]] = {

    val allDepsByConfig = depsByConfig.map {
      case (config, deps) =>
        config -> res(config).subset(deps).minDependencies
    }

    val filteredAllDepsByConfig = allDepsByConfig.map {
      case (config, allDeps) =>
        val allExtendedConfigs = configs.getOrElse(config, Set.empty) - config
        val inherited = allExtendedConfigs
          .flatMap(allDepsByConfig.getOrElse(_, Set.empty))

        config -> (allDeps -- inherited)
    }

    filteredAllDepsByConfig
  }

  // Move back to coursier.util (in core module) after 1.0?
  private def dependenciesWithConfig(
    res: Map[Configuration, Resolution],
    depsByConfig: Map[Configuration, Set[Dependency]],
    configs: Map[Configuration, Set[Configuration]]
  ): Set[Dependency] =
    allDependenciesByConfig(res, depsByConfig, configs)
      .flatMap {
        case (config, deps) =>
          deps.map(dep => dep.copy(configuration = config --> dep.configuration))
      }
      .groupBy(_.copy(configuration = Configuration.empty))
      .map {
        case (dep, l) =>
          dep.copy(configuration = Configuration.join(l.map(_.configuration).toSeq: _*))
      }
      .toSet

  private def report(
    shadedConfigOpt: Option[(String, Configuration)],
    artifactFilesOrErrors0: Map[Artifact, Either[FileError, File]],
    classifiers: Option[Seq[Classifier]],
    configs: Map[Configuration, Set[Configuration]],
    currentProject: Project,
    res: Map[Set[Configuration], Resolution],
    ignoreArtifactErrors: Boolean,
    includeSignatures: Boolean,
    sbtBootJarOverrides: Map[(Module, String), File],
    verbosityLevel: Int,
    log: Logger
  ) = {

    val configResolutions = res.flatMap {
      case (configs, r) =>
        configs.iterator.map((_, r))
    }

    def report = {

      val depsByConfig = grouped(currentProject.dependencies)(
        config =>
          shadedConfigOpt match {
            case Some((baseConfig, `config`)) =>
              Configuration(baseConfig)
            case _ =>
              config
          }
      )

      if (verbosityLevel >= 2) {
        val finalDeps = dependenciesWithConfig(
          configResolutions,
          depsByConfig.map { case (k, l) => k -> l.toSet },
          configs
        )

        val projCache = res.values.foldLeft(Map.empty[ModuleVersion, Project])(_ ++ _.projectCache.mapValues(_._2))
        val repr = Print.dependenciesUnknownConfigs(finalDeps.toVector, projCache)
        log.info(repr.split('\n').map("  " + _).mkString("\n"))
      }

      val artifactFiles = artifactFilesOrErrors0.collect {
        case (artifact, Right(file)) =>
          artifact -> file
      }

      val artifactErrors = artifactFilesOrErrors0
        .toVector
        .collect {
          case (a, Left(err)) if !a.optional || !err.notFound =>
            a -> err
        }

      if (artifactErrors.nonEmpty) {
        val error = ResolutionError.DownloadErrors(artifactErrors.map(_._2))

        if (ignoreArtifactErrors)
          log.warn(error.description(verbosityLevel >= 1))
        else
          error.throwException()
      }

      // can be non empty only if ignoreArtifactErrors is true or some optional artifacts are not found
      val erroredArtifacts = artifactFilesOrErrors0.collect {
        case (artifact, Left(_)) =>
          artifact
      }.toSet

      ToSbt.updateReport(
        depsByConfig,
        configResolutions,
        configs,
        classifiers,
        artifactFileOpt(
          sbtBootJarOverrides,
          artifactFiles,
          erroredArtifacts
        ),
        log,
        includeSignatures = includeSignatures
      )
    }

    // let's update only one module at once, for a better output
    // Downloads are already parallel, no need to parallelize further anyway
    synchronized {
      report
    }
  }

  private def grouped[K, V](map: Seq[(K, V)])(mapKey: K => K): Map[K, Seq[V]] =
    map
      .groupBy(t => mapKey(t._1))
      .mapValues(_.map(_._2))
      .iterator
      .toMap

  def updateTask(
    shadedConfigOpt: Option[(String, Configuration)],
    withClassifiers: Boolean,
    sbtClassifiers: Boolean = false,
    ignoreArtifactErrors: Boolean = false,
    includeSignatures: Boolean = false
  ): Def.Initialize[sbt.Task[UpdateReport]] = Def.taskDyn {

    val so = Organization(scalaOrganization.value)
    val internalSbtScalaProvider = appConfiguration.value.provider.scalaProvider
    val sbtBootJarOverrides = SbtBootJars(
      so, // this seems plain wrong - this assumes that the scala org of the project is the same
      // as the one that started SBT. This will scrap the scala org specific JARs by the ones
      // that booted SBT, even if the latter come from the standard org.scala-lang org.
      // But SBT itself does it this way, and not doing so may make two different versions
      // of the scala JARs land in the classpath...
      internalSbtScalaProvider.version(),
      internalSbtScalaProvider.jars()
    )

    val sv = scalaVersion.value
    val sbv = scalaBinaryVersion.value

    val currentProjectTask =
      if (sbtClassifiers)
        Def.task(FromSbt.sbtClassifiersProject(coursierSbtClassifiersModule.value, sv, sbv))
      else
        Def.task {
          val proj = coursierProject.value
          val publications = coursierPublications.value

          proj.copy(publications = publications)
        }

    val log = streams.value.log

    val verbosityLevel = coursierVerbosity.value

    val resTask =
      if (withClassifiers && sbtClassifiers)
        Def.task {
          val cm = coursierSbtClassifiersModule.value
          val classifiersRes = coursierSbtClassifiersResolution.value
          Map(cm.configurations.map(c => Configuration(c.name)).toSet -> classifiersRes)
        }
      else
        Def.task(coursierResolutions.value)

    // we should be able to call .value on that one here, its conditions don't originate from other tasks
    val artifactFilesOrErrors0Task =
      if (withClassifiers) {
        if (sbtClassifiers)
          Keys.coursierSbtClassifiersArtifacts
        else
          Keys.coursierClassifiersArtifacts
      } else if (includeSignatures)
        Keys.coursierSignedArtifacts
      else
        Keys.coursierArtifacts

    val configsTask: sbt.Def.Initialize[sbt.Task[Map[Configuration, Set[Configuration]]]] =
      if (withClassifiers && sbtClassifiers)
        Def.task {
          val cm = coursierSbtClassifiersModule.value
          cm.configurations.map(c => Configuration(c.name) -> Set(Configuration(c.name))).toMap
        }
      else
        Def.task {
          val configs0 = coursierConfigurations.value

          shadedConfigOpt.fold(configs0) {
            case (baseConfig, shadedConfig) =>
              val baseConfig0 = Configuration(baseConfig)
              (configs0 - shadedConfig) + (
                baseConfig0 -> (configs0.getOrElse(baseConfig0, Set()) - shadedConfig)
              )
          }
        }

    val classifiersTask: sbt.Def.Initialize[sbt.Task[Option[Seq[Classifier]]]] =
      if (withClassifiers) {
        if (sbtClassifiers)
          Def.task {
            val cm = coursierSbtClassifiersModule.value
            Some(cm.classifiers.map(Classifier(_)))
          }
        else
          Def.task(Some(transitiveClassifiers.value.map(Classifier(_))))
      } else
        Def.task(None)

    Def.taskDyn {

      val currentProject = currentProjectTask.value
      val res = resTask.value

      val key = SbtCoursierCache.ReportKey(
        currentProject,
        res,
        withClassifiers,
        sbtClassifiers,
        ignoreArtifactErrors
      )

      SbtCoursierCache.default.reportOpt(key) match {
        case Some(report) =>
          Def.task(report)
        case None =>
          Def.task {

            val artifactFilesOrErrors0 = artifactFilesOrErrors0Task.value
            val classifiers = classifiersTask.value
            val configs = configsTask.value

            val rep = report(
              shadedConfigOpt,
              artifactFilesOrErrors0,
              classifiers,
              configs,
              currentProject,
              res,
              ignoreArtifactErrors,
              includeSignatures,
              sbtBootJarOverrides,
              verbosityLevel,
              log
            )
            SbtCoursierCache.default.putReport(key, rep)
            rep
          }
      }
    }
  }

}
