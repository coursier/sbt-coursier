package coursier.sbtcoursier

import java.io.File

import coursier.core.Resolution.ModuleVersion
import coursier.core._
import coursier.util.Print
import sbt.librarymanagement.UpdateReport
import sbt.util.Logger

object UpdateRun {

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

  def update(
    params: UpdateParams,
    verbosityLevel: Int,
    log: Logger
  ): Either[ResolutionError.DownloadErrors, UpdateReport] = {

    val configResolutions = params.res.flatMap {
      case (configs, r) =>
        configs.iterator.map((_, r))
    }

    val depsByConfig = grouped(params.dependencies)(
      config =>
        params.shadedConfigOpt match {
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
        params.configs
      )

      val projCache = params.res.values.foldLeft(Map.empty[ModuleVersion, Project])(_ ++ _.projectCache.mapValues(_._2))
      val repr = Print.dependenciesUnknownConfigs(finalDeps.toVector, projCache)
      log.info(repr.split('\n').map("  " + _).mkString("\n"))
    }

    val artifactFiles = params.artifacts.collect {
      case (artifact, Right(file)) =>
        artifact -> file
    }

    val artifactErrors = params
      .artifacts
      .toVector
      .collect {
        case (a, Left(err)) if !a.optional || !err.notFound =>
          a -> err
      }

    // can be non empty only if ignoreArtifactErrors is true or some optional artifacts are not found
    val erroredArtifacts = params
      .artifacts
      .collect {
        case (artifact, Left(_)) =>
          artifact
      }
      .toSet

    def report =
      ToSbt.updateReport(
        depsByConfig,
        configResolutions,
        params.configs,
        params.classifiers,
        artifactFileOpt(
          params.sbtBootJarOverrides,
          artifactFiles,
          erroredArtifacts
        ),
        log,
        includeSignatures = params.includeSignatures
      )

    if (artifactErrors.isEmpty)
      Right(report)
    else {
      val error = ResolutionError.DownloadErrors(artifactErrors.map(_._2))

      if (params.ignoreArtifactErrors) {
        log.warn(error.description(verbosityLevel >= 1))
        Right(report)
      } else
        Left(error)
    }
  }

  private def grouped[K, V](map: Seq[(K, V)])(mapKey: K => K): Map[K, Seq[V]] =
    map
      .groupBy(t => mapKey(t._1))
      .mapValues(_.map(_._2))
      .iterator
      .toMap

}
