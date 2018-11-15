package coursier.sbtcoursier

import java.io.File
import java.util.concurrent.{ExecutorService, Executors}

import coursier.{Artifact, Cache, CachePolicy, FileError, FromSbt}
import coursier.core._
import coursier.interop.scalaz._
import coursier.sbtcoursier.Keys._
import coursier.sbtcoursier.Structure._
import sbt.librarymanagement.{Artifact => _, Configuration => _, _}
import sbt.Def
import sbt.Keys._
import sbt.util.Logger
import scalaz.concurrent.{Strategy, Task}

import scala.concurrent.duration.Duration

object ArtifactsTasks {

  def coursierPublicationsTask(
    configsMap: (sbt.Configuration, Configuration)*
  ): Def.Initialize[sbt.Task[Seq[(Configuration, Publication)]]] =
    Def.task {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value
      val projId = sbt.Keys.projectID.value
      val sv = sbt.Keys.scalaVersion.value
      val sbv = sbt.Keys.scalaBinaryVersion.value
      val ivyConfs = sbt.Keys.ivyConfigurations.value

      val sourcesConfigOpt =
        if (ivyConfigurations.value.exists(_.name == "sources"))
          Some(Configuration("sources"))
        else
          None

      val docsConfigOpt =
        if (ivyConfigurations.value.exists(_.name == "docs"))
          Some(Configuration("docs"))
        else
          None

      val sbtBinArtifacts =
        for ((config, targetConfig) <- configsMap) yield {

          val publish = publishArtifact
            .in(projectRef)
            .in(packageBin)
            .in(config)
            .getOrElse(state, false)

          if (publish)
            artifact
              .in(projectRef)
              .in(packageBin)
              .in(config)
              .find(state)
              .map(targetConfig -> _)
          else
            None
        }

      val sbtSourceArtifacts =
        for ((config, targetConfig) <- configsMap) yield {

          val publish = publishArtifact
            .in(projectRef)
            .in(packageSrc)
            .in(config)
            .getOrElse(state, false)

          if (publish)
            artifact
              .in(projectRef)
              .in(packageSrc)
              .in(config)
              .find(state)
              .map(sourcesConfigOpt.getOrElse(targetConfig) -> _)
          else
            None
        }

      val sbtDocArtifacts =
        for ((config, targetConfig) <- configsMap) yield {

          val publish = publishArtifact
            .in(projectRef)
            .in(packageDoc)
            .in(config)
            .getOrElse(state, false)

          if (publish)
            artifact
              .in(projectRef)
              .in(packageDoc)
              .in(config)
              .find(state)
              .map(docsConfigOpt.getOrElse(targetConfig) -> _)
          else
            None
        }

      val sbtArtifacts = sbtBinArtifacts ++ sbtSourceArtifacts ++ sbtDocArtifacts

      def artifactPublication(artifact: sbt.Artifact) = {

        val name = FromSbt.sbtCrossVersionName(
          artifact.name,
          projId.crossVersion,
          sv,
          sbv
        )

        Publication(
          name,
          Type(artifact.`type`),
          Extension(artifact.extension),
          artifact.classifier.fold(Classifier.empty)(Classifier(_))
        )
      }

      val sbtArtifactsPublication = sbtArtifacts.collect {
        case Some((config, artifact)) =>
          config -> artifactPublication(artifact)
      }

      val stdArtifactsSet = sbtArtifacts.flatMap(_.map { case (_, a) => a }.toSeq).toSet

      // Second-way of getting artifacts from SBT
      // No obvious way of getting the corresponding  publishArtifact  value for the ones
      // only here, it seems.
      val extraSbtArtifacts = sbt.Keys.artifacts.in(projectRef).getOrElse(state, Nil)
        .filterNot(stdArtifactsSet)

      // Seems that SBT does that - if an artifact has no configs,
      // it puts it in all of them. See for example what happens to
      // the standalone JAR artifact of the coursier cli module.
      def allConfigsIfEmpty(configs: Iterable[ConfigRef]): Iterable[ConfigRef] =
        if (configs.isEmpty) ivyConfs.filter(_.isPublic).map(c => ConfigRef(c.name)) else configs

      val extraSbtArtifactsPublication = for {
        artifact <- extraSbtArtifacts
        config <- allConfigsIfEmpty(artifact.configurations.map(x => ConfigRef(x.name)))
        // FIXME If some configurations from artifact.configurations are not public, they may leak here :\
      } yield Configuration(config.name) -> artifactPublication(artifact)

      sbtArtifactsPublication ++ extraSbtArtifactsPublication
    }

  def artifacts(
    params: ArtifactsParams,
    verbosityLevel: Int,
    log: Logger
  ): Map[Artifact, Either[FileError, File]] = {

    val allArtifacts0 = params.res.flatMap(_.dependencyArtifacts(params.classifiers)).map(_._3)

    val allArtifacts =
      if (params.includeSignatures)
        allArtifacts0.flatMap { a =>
          val sigOpt = a.extra.get("sig")
          Seq(a) ++ sigOpt.toSeq
        }
      else
        allArtifacts0

    // let's update only one module at once, for a better output
    // Downloads are already parallel, no need to parallelize further anyway
    Lock.lock.synchronized {

      var pool: ExecutorService = null
      var artifactsLogger: Cache.Logger = null

      val printOptionalMessage = verbosityLevel >= 0 && verbosityLevel <= 1

      val artifactFilesOrErrors = try {
        pool = Executors.newFixedThreadPool(params.parallelDownloads, Strategy.DefaultDaemonThreadFactory)
        artifactsLogger = params.createLogger()

        val artifactFileOrErrorTasks = allArtifacts.toVector.distinct.map { a =>
          def f(p: CachePolicy) =
            Cache.file[Task](
              a,
              params.cache,
              p,
              checksums = params.artifactsChecksums,
              logger = Some(artifactsLogger),
              pool = pool,
              ttl = params.ttl
            )

          params.cachePolicies.tail
            .foldLeft(f(params.cachePolicies.head))(_ orElse f(_))
            .run
            .map((a, _))
        }

        val artifactInitialMessage =
          if (verbosityLevel >= 0)
            s"Fetching artifacts of ${params.projectName}" +
              (if (params.sbtClassifiers) " (sbt classifiers)" else "")
          else
            ""

        if (verbosityLevel >= 2)
          log.info(artifactInitialMessage)

        artifactsLogger.init(if (printOptionalMessage) log.info(artifactInitialMessage))

        Task.gatherUnordered(artifactFileOrErrorTasks).unsafePerformSyncAttempt.toEither match {
          case Left(ex) =>
            ResolutionError.UnknownDownloadException(ex)
              .throwException()
          case Right(l) =>
            l.toMap
        }
      } finally {
        if (pool != null)
          pool.shutdown()
        if (artifactsLogger != null)
          if ((artifactsLogger.stopDidPrintSomething() && printOptionalMessage) || verbosityLevel >= 2)
            log.info(
              s"Fetched artifacts of ${params.projectName}" +
                (if (params.sbtClassifiers) " (sbt classifiers)" else "")
            )
      }

      artifactFilesOrErrors
    }
  }

  def artifactsTask(
    withClassifiers: Boolean,
    sbtClassifiers: Boolean = false,
    ignoreArtifactErrors: Boolean = false,
    includeSignatures: Boolean = false
  ): Def.Initialize[sbt.Task[Map[Artifact, Either[FileError, File]]]] = {

    val resTask: sbt.Def.Initialize[sbt.Task[Seq[Resolution]]] =
      if (withClassifiers && sbtClassifiers)
        Def.task(Seq(coursierSbtClassifiersResolution.value))
      else
        Def.task(coursierResolutions.value.values.toVector)

    val classifiersTask: sbt.Def.Initialize[sbt.Task[Option[Seq[Classifier]]]] =
      if (withClassifiers) {
        if (sbtClassifiers)
          Def.task(Some(coursierSbtClassifiersModule.value.classifiers.map(Classifier(_))))
        else
          Def.task(Some(transitiveClassifiers.value.map(Classifier(_))))
      } else
        Def.task(None)

    Def.task {

      val projectName = thisProjectRef.value.project

      val parallelDownloads = coursierParallelDownloads.value
      val artifactsChecksums = coursierArtifactsChecksums.value
      val cachePolicies = coursierCachePolicies.value
      val ttl = coursierTtl.value
      val cache = coursierCache.value
      val createLogger = coursierCreateLogger.value

      val log = streams.value.log

      val verbosityLevel = coursierVerbosity.value

      val classifiers = classifiersTask.value
      val res = resTask.value

      val params = ArtifactsParams(
        classifiers,
        res,
        includeSignatures,
        parallelDownloads,
        createLogger,
        cache,
        artifactsChecksums,
        ttl,
        cachePolicies,
        projectName,
        sbtClassifiers
      )

      artifacts(
        params,
        verbosityLevel,
        log
      )
    }
  }

}
