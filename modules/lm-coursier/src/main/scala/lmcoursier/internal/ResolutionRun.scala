package lmcoursier.internal

import coursier.{Resolution, Resolve}
import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import coursier.cache.loggers.{FallbackRefreshDisplay, ProgressBarRefreshDisplay, RefreshLogger}
import coursier.core._
import coursier.error.ResolutionError
import coursier.error.ResolutionError.{CantDownloadModule, Several, Simple}
import coursier.ivy.IvyRepository
import coursier.maven.MavenRepositoryLike
import coursier.params.rule.RuleResolution
import coursier.util.Task
import sbt.util.Logger

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

// private[coursier]
object ResolutionRun {

  private def resolution(
    params: ResolutionParams,
    verbosityLevel: Int,
    log: Logger,
    configs: Set[Configuration],
    startingResolutionOpt: Option[Resolution]
  ): Either[coursier.error.ResolutionError, Resolution] = {

    val isScalaToolConfig = configs(Configuration("scala-tool"))
    // Ref coursier/coursier#1340 coursier/coursier#1442
    // This treats ScalaTool as a sandbox configuration isolated from other subprojects.
    // Likely this behavior is needed only for ScalaTool configuration where the scala-xml
    // build's ScalaTool configuration transitively loops back to scala-xml's Compile artifacts.
    // In most other cases, it's desirable to allow "x->compile" relationship.
    def isSandboxConfig: Boolean = isScalaToolConfig

    val repositories =
      params.internalRepositories.drop(if (isSandboxConfig) 1 else 0) ++
        params.mainRepositories ++
        params.fallbackDependenciesRepositories

    val rules = params.strictOpt.map(s => Seq((s, RuleResolution.Fail))).getOrElse(Nil)

    val printOptionalMessage = verbosityLevel >= 0 && verbosityLevel <= 1

    def depsRepr(deps: Seq[(Configuration, Dependency)]) =
      deps.map { case (config, dep) =>
        s"${dep.module}:${dep.version}:${config.value}->${dep.configuration.value}"
      }.sorted.distinct

    val initialMessage =
      Seq(
        if (verbosityLevel >= 0)
          Seq(s"Updating ${params.projectName}" + (if (params.sbtClassifiers) " (sbt classifiers)" else ""))
        else
          Nil,
        if (verbosityLevel >= 2)
          depsRepr(params.dependencies).map(depRepr =>
            s"  $depRepr"
          )
        else
          Nil
      ).flatten.mkString("\n")

    if (verbosityLevel >= 2) {
      val repoReprs = repositories.map {
        case r: IvyRepository =>
          s"ivy:${r.pattern}"
        case _: InterProjectRepository =>
          "inter-project"
        case r: MavenRepositoryLike =>
          r.root
        case r =>
          // should not happen
          r.toString
      }

      log.info(
        "Repositories:\n" +
          repoReprs.map("  " + _).mkString("\n")
      )
    }

    if (verbosityLevel >= 2)
      log.info(initialMessage)

    val resolveTask: Resolve[Task] = {
      Resolve()
        // re-using various caches from a resolution of a configuration we extend
        .withInitialResolution(startingResolutionOpt)
        .withDependencies(
          params.dependencies.collect {
            case (config, dep) if configs(config) =>
              dep
          }
        )
        .withRepositories(repositories)
        .withResolutionParams(
          params
            .params
            .addForceVersion((if (isSandboxConfig) Nil else params.interProjectDependencies.map(_.moduleVersion)): _*)
            .withForceScalaVersion(params.autoScalaLibOpt.nonEmpty)
            .withScalaVersionOpt(params.autoScalaLibOpt.map(_._2))
            .withTypelevel(params.params.typelevel)
            .withRules(rules)
        )
        .withCache(
          params
            .cache
            .withLogger(
              params.loggerOpt.getOrElse {
                RefreshLogger.create(
                  if (RefreshLogger.defaultFallbackMode)
                    new FallbackRefreshDisplay()
                  else
                    ProgressBarRefreshDisplay.create(
                      if (printOptionalMessage) log.info(initialMessage),
                      if (printOptionalMessage || verbosityLevel >= 2)
                        log.info(s"Resolved ${params.projectName} dependencies")
                    )
                )
              }
            )
        )
    }

    def retryOnFailure(
      maxAttempts: Int,
      retry: Int => Future[Either[ResolutionError, Resolution]],
      attempt: Int,
      resolutionError: ResolutionError,
      period: FiniteDuration,
      scheduler: ScheduledExecutorService
    )(implicit
      ec: ExecutionContext
    ): Future[Either[ResolutionError, Resolution]] =
      if (attempt >= maxAttempts) {
        log.error(s"Failed, maximum iterations ($maxAttempts) reached")
        Future.successful(Left(resolutionError))
      }
      else {
        log.info(s"Attempt $attempt failed: $resolutionError")

        // Backoff retry
        val timeToWait = (period * Math.pow(2, attempt)) match {
          case f: FiniteDuration => f
          case _: Duration       => sys.error("Cannot happen")
        }
        val delay: Future[Unit] = Task.completeAfter(scheduler, timeToWait).future()
        delay.flatMap { _ =>
          retry(attempt + 1)
        }
      }

    val (period, maxAttempts) = params.retry
    val finalResult: Either[ResolutionError, Resolution] = {
      val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory {
          val defaultThreadFactory: ThreadFactory = Executors.defaultThreadFactory()

          def newThread(r: Runnable): Thread = {
            val t = defaultThreadFactory.newThread(r)
            t.setDaemon(true)
            t.setName("retry-handler")
            t
          }
        }
      )
      implicit val ec: ExecutionContext = resolveTask.cache.ec

      def retry(attempt: Int): Future[Either[ResolutionError, Resolution]] =
        resolveTask
          .io
          .map(Right(_))
          .handle { case ex: ResolutionError => Left(ex) }
          .future()
          .flatMap {
            case Left(e: ResolutionError) =>
              val isCantDownload = e.errors.exists(_.isInstanceOf[CantDownloadModule])
              //should not retry in case "not found" error thrown
              def isNotFound = e.errors.exists(isNotFoundError(_))
              if (isCantDownload && !isNotFound)
                retryOnFailure(maxAttempts, retry, attempt, e, period, scheduler)
              else
                Future.successful(Left(e))
            case Right(res) =>
              Future.successful(Right(res))
          }

      Await.result(retry(1), Duration.Inf)
    }

    finalResult match {
      case Left(err) if params.missingOk => Right(err.resolution)
      case others => others
    }
  }

  def resolutions(
    params: ResolutionParams,
    verbosityLevel: Int,
    log: Logger
  ): Either[coursier.error.ResolutionError, Map[Configuration, Resolution]] = {

    // TODO Warn about possible duplicated modules from source repositories?

    if (verbosityLevel >= 2) {
      log.info("InterProjectRepository")
      for (p <- params.interProjectDependencies)
        log.info(s"  ${p.module}:${p.version}")
    }

    SbtCoursierCache.default.resolutionOpt(params.resolutionKey).map(Right(_)).getOrElse {
      val resOrError =
        Lock.maybeSynchronized(needsLock = params.loggerOpt.nonEmpty || !RefreshLogger.defaultFallbackMode) {
          var map = new mutable.HashMap[Configuration, Resolution]
          val either = params.orderedConfigs.foldLeft[Either[coursier.error.ResolutionError, Unit]](Right(())) {
            case (acc, (config, extends0)) =>
              for {
                _ <- acc
                initRes = {
                  val it = extends0.iterator.flatMap(map.get(_).iterator)
                  if (it.hasNext) Some(it.next())
                  else None
                }
                allExtends = params.allConfigExtends.getOrElse(config, Set.empty)
                res <- resolution(params, verbosityLevel, log, allExtends, initRes)
              } yield {
                map += config -> res
                ()
              }
          }
          either.map(_ => map.toMap)
        }
      for (res <- resOrError)
        SbtCoursierCache.default.putResolution(params.resolutionKey, res)
      resOrError
    }
  }

  private def isNotFoundError(resolutionError: ResolutionError): Boolean = {
    resolutionError.getMessage.toLowerCase().contains("not found")
  }
}
