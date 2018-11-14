package coursier.sbtcoursier

import java.io.File
import java.net.URL
import java.util.concurrent.{ExecutorService, Executors}

import coursier.{Cache, FallbackDependenciesRepository, Fetch, FromSbt, ProjectCache, Resolution, moduleNameString}
import coursier.core._
import coursier.extra.Typelevel
import coursier.interop.scalaz._
import coursier.ivy.{IvyRepository, PropertiesPattern}
import coursier.maven.MavenRepository
import coursier.sbtcoursier.Keys._
import coursier.util.Print
import sbt.Def
import sbt.Keys._

import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scalaz.concurrent.Strategy

object Tasks {

  private def forcedScalaModules(
    scalaOrganization: Organization,
    scalaVersion: String
  ): Map[Module, String] =
    Map(
      Module(scalaOrganization, name"scala-library", Map.empty) -> scalaVersion,
      Module(scalaOrganization, name"scala-compiler", Map.empty) -> scalaVersion,
      Module(scalaOrganization, name"scala-reflect", Map.empty) -> scalaVersion,
      Module(scalaOrganization, name"scalap", Map.empty) -> scalaVersion
    )

  private def exceptionPatternParser(): String => coursier.ivy.Pattern = {

    val props = sys.props.toMap

    val extraProps = new ArrayBuffer[(String, String)]

    def addUriProp(key: String): Unit =
      for (b <- props.get(key)) {
        val uri = new File(b).toURI.toString
        extraProps += s"$key.uri" -> uri
      }

    addUriProp("sbt.global.base")
    addUriProp("user.home")

    {
      s =>
        val p = PropertiesPattern.parse(s) match {
          case Left(err) =>
            throw new Exception(s"Cannot parse pattern $s: $err")
          case Right(p) =>
            p
        }

        p.substituteProperties(props ++ extraProps) match {
          case Left(err) =>
            throw new Exception(err)
          case Right(p) =>
            p
        }
    }
  }

  private def globalPluginPatterns(sbtVersion: String): Seq[coursier.ivy.Pattern] = {

    // FIXME get the 0.13 automatically?
    val defaultRawPattern = s"$${sbt.global.base.uri-$${user.home.uri}/.sbt/$sbtVersion}/plugins/target" +
      "/resolution-cache/" +
      "[organization]/[module](/scala_[scalaVersion])(/sbt_[sbtVersion])/[revision]/resolved.xml.[ext]"

    // seems to be required in more recent versions of sbt (since 0.13.16?)
    val extraRawPattern = s"$${sbt.global.base.uri-$${user.home.uri}/.sbt/$sbtVersion}/plugins/target" +
      "(/scala-[scalaVersion])(/sbt-[sbtVersion])" +
      "/resolution-cache/" +
      "[organization]/[module](/scala_[scalaVersion])(/sbt_[sbtVersion])/[revision]/resolved.xml.[ext]"

    val p = exceptionPatternParser()

    Seq(
      defaultRawPattern,
      extraRawPattern
    ).map(p)
  }


  private val noOptionalFilter: Option[Dependency => Boolean] = Some(dep => !dep.optional)
  private val typelevelOrgSwap: Option[Dependency => Dependency] = Some(Typelevel.swap(_))


  def resolutionsTask(
    sbtClassifiers: Boolean = false
  ): Def.Initialize[sbt.Task[Map[Set[Configuration], coursier.Resolution]]] = Def.taskDyn {
    val projectName = thisProjectRef.value.project

    val sv = scalaVersion.value
    val sbv = scalaBinaryVersion.value

    val currentProjectTask: sbt.Def.Initialize[sbt.Task[(Project, Seq[(Module, String, URL, Boolean)], Seq[Set[Configuration]])]] =
      if (sbtClassifiers)
        Def.task {
          val cm = coursierSbtClassifiersModule.value
          val proj = FromSbt.sbtClassifiersProject(cm, sv, sbv)

          val fallbackDeps = FromSbt.fallbackDependencies(
            cm.dependencies,
            sv,
            sbv
          )

          (proj, fallbackDeps, Vector(cm.configurations.map(c => Configuration(c.name)).toSet))
        }
      else
        Def.task {
          val baseConfigGraphs = coursierConfigGraphs.value
          (coursierProject.value.copy(publications = coursierPublications.value), coursierFallbackDependencies.value, baseConfigGraphs)
        }

    val interProjectDependencies = coursierInterProjectDependencies.value

    val parallelDownloads = coursierParallelDownloads.value
    val checksums = coursierChecksums.value
    val maxIterations = coursierMaxIterations.value
    val cachePolicies = coursierCachePolicies.value
    val ttl = coursierTtl.value
    val cache = coursierCache.value
    val createLogger = coursierCreateLogger.value

    val log = streams.value.log

    // are these always defined? (e.g. for Java only projects?)
    val so = Organization(scalaOrganization.value)

    val userForceVersions = dependencyOverrides
      .value
      .map(FromSbt.moduleVersion(_, sv, sbv))
      .toMap

    val resolversTask =
      if (sbtClassifiers)
        Def.task(coursierSbtResolvers.value)
      else
        Def.task(coursierRecursiveResolvers.value.distinct)

    val verbosityLevel = coursierVerbosity.value

    val userEnabledProfiles = mavenProfiles.value

    val typelevel = Organization(scalaOrganization.value) == Typelevel.typelevelOrg

    val globalPluginsRepos =
      for (p <- globalPluginPatterns(sbtBinaryVersion.value))
        yield IvyRepository.fromPattern(
          p,
          withChecksums = false,
          withSignatures = false,
          withArtifacts = false
        )

    val interProjectRepo = InterProjectRepository(interProjectDependencies)

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

    val useSbtCredentials = coursierUseSbtCredentials.value

    val authenticationByHostTask =
      if (useSbtCredentials)
        Def.task {
          sbt.Keys.credentials.value
            .flatMap {
              case dc: sbt.DirectCredentials => List(dc)
              case fc: sbt.FileCredentials =>
                sbt.Credentials.loadCredentials(fc.path) match {
                  case Left(err) =>
                    log.warn(s"$err, ignoring it")
                    Nil
                  case Right(dc) => List(dc)
                }
            }
            .map { c =>
              c.host -> Authentication(c.userName, c.passwd)
            }
            .toMap
        }
      else
        Def.task(Map.empty[String, Authentication])

    val authenticationByRepositoryId = coursierCredentials.value.mapValues(_.authentication)

    def resTask(
      currentProject: Project,
      fallbackDependencies: Seq[(Module, String, URL, Boolean)],
      configGraphs: Seq[Set[Configuration]],
      repositories: Seq[Repository],
      internalRepositories: Seq[Repository],
      allStartRes: Map[Set[Configuration], coursier.Resolution]
    ) = Def.task {

      def resolution(startRes: Resolution) = {

        var pool: ExecutorService = null
        var resLogger: Cache.Logger = null

        val printOptionalMessage = verbosityLevel >= 0 && verbosityLevel <= 1

        val res = try {
          pool = Executors.newFixedThreadPool(parallelDownloads, Strategy.DefaultDaemonThreadFactory)
          resLogger = createLogger()

          val fetch = Fetch.from(
            repositories,
            Cache.fetch(cache, cachePolicies.head, checksums = checksums, logger = Some(resLogger), pool = pool, ttl = ttl),
            cachePolicies.tail.map(p =>
              Cache.fetch(cache, p, checksums = checksums, logger = Some(resLogger), pool = pool, ttl = ttl)
            ): _*
          )

          def depsRepr(deps: Seq[(Configuration, Dependency)]) =
            deps.map { case (config, dep) =>
              s"${dep.module}:${dep.version}:${config.value}->${dep.configuration.value}"
            }.sorted.distinct

          if (verbosityLevel >= 2) {
            val repoReprs = repositories.map {
              case r: IvyRepository =>
                s"ivy:${r.pattern}"
              case r: InterProjectRepository =>
                "inter-project"
              case r: MavenRepository =>
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

          val initialMessage =
            Seq(
              if (verbosityLevel >= 0)
                Seq(s"Updating $projectName" + (if (sbtClassifiers) " (sbt classifiers)" else ""))
              else
                Nil,
              if (verbosityLevel >= 2)
                depsRepr(currentProject.dependencies).map(depRepr =>
                  s"  $depRepr"
                )
              else
                Nil
            ).flatten.mkString("\n")

          if (verbosityLevel >= 2)
            log.info(initialMessage)

          resLogger.init(if (printOptionalMessage) log.info(initialMessage))

          startRes
            .process
            .run(fetch, maxIterations)
            .unsafePerformSyncAttempt
            .toEither
            .left
            .map(ex =>
              ResolutionError.UnknownException(ex)
                .throwException()
            )
            .merge
        } finally {
          if (pool != null)
            pool.shutdown()
          if (resLogger != null)
            if ((resLogger.stopDidPrintSomething() && printOptionalMessage) || verbosityLevel >= 2)
              log.info(s"Resolved $projectName dependencies")
        }

        if (!res.isDone)
          ResolutionError.MaximumIterationsReached
            .throwException()

        if (res.conflicts.nonEmpty) {
          val projCache = res.projectCache.mapValues { case (_, p) => p }

          ResolutionError.Conflicts(
            "Conflict(s) in dependency resolution:\n  " +
              Print.dependenciesUnknownConfigs(res.conflicts.toVector, projCache)
          ).throwException()
        }

        if (res.errors.nonEmpty) {
          val internalRepositoriesLen = internalRepositories.length
          val errors =
            if (repositories.length > internalRepositoriesLen)
            // drop internal repository errors
              res.errors.map {
                case (dep, errs) =>
                  dep -> errs.drop(internalRepositoriesLen)
              }
            else
              res.errors

          ResolutionError.MetadataDownloadErrors(errors)
            .throwException()
        }

        res
      }

      // let's update only one module at once, for a better output
      // Downloads are already parallel, no need to parallelize further anyway
      synchronized {
        allStartRes.map {
          case (config, startRes) =>
            config -> resolution(startRes)
        }
      }
    }

    Def.taskDyn {

      val (currentProject, fallbackDependencies, configGraphs) = currentProjectTask.value

      val autoScalaLib = autoScalaLibrary.value

      val resolvers = resolversTask.value

      // TODO Warn about possible duplicated modules from source repositories?

      val authenticationByHost = authenticationByHostTask.value

      val fallbackDependenciesRepositories =
        if (fallbackDependencies.isEmpty)
          Nil
        else {
          val map = fallbackDependencies.map {
            case (mod, ver, url, changing) =>
              (mod, ver) -> ((url, changing))
          }.toMap

          Seq(
            FallbackDependenciesRepository(map)
          )
        }

      if (verbosityLevel >= 2) {
        log.info("InterProjectRepository")
        for (p <- interProjectDependencies)
          log.info(s"  ${p.module}:${p.version}")
      }

      def withAuthenticationByHost(repo: Repository, credentials: Map[String, Authentication]): Repository = {

        def httpHost(s: String) =
          if (s.startsWith("http://") || s.startsWith("https://"))
            Try(Cache.url(s).getHost).toOption
          else
            None

        repo match {
          case m: MavenRepository =>
            if (m.authentication.isEmpty)
              httpHost(m.root).flatMap(credentials.get).fold(m) { auth =>
                m.copy(authentication = Some(auth))
              }
            else
              m
          case i: IvyRepository =>
            if (i.authentication.isEmpty) {
              val base = i.pattern.chunks.takeWhile {
                case _: coursier.ivy.Pattern.Chunk.Const => true
                case _ => false
              }.map(_.string).mkString

              httpHost(base).flatMap(credentials.get).fold(i) { auth =>
                i.copy(authentication = Some(auth))
              }
            } else
              i
          case _ =>
            repo
        }
      }

      val internalRepositories = globalPluginsRepos :+ interProjectRepo

      val repositories =
        internalRepositories ++
          resolvers.flatMap { resolver =>
            FromSbt.repository(
              resolver,
              ivyProperties,
              log,
              authenticationByRepositoryId.get(resolver.name)
            )
          }.map(withAuthenticationByHost(_, authenticationByHost)) ++
          fallbackDependenciesRepositories

      val parentProjectCache: ProjectCache = coursierParentProjectCache.value
        .get(resolvers)
        .map(_.foldLeft[ProjectCache](Map.empty)(_ ++ _))
        .getOrElse(Map.empty)

      def startRes(configs: Set[Configuration]) = Resolution(
        currentProject
          .dependencies
          .collect {
            case (config, dep) if configs(config) =>
              dep
          }
          .toSet,
        filter = noOptionalFilter,
        userActivations =
          if (userEnabledProfiles.isEmpty)
            None
          else
            Some(userEnabledProfiles.iterator.map(_ -> true).toMap),
        forceVersions =
          // order matters here
          userForceVersions ++
            (if (autoScalaLib && (configs(Configuration.compile) || configs(Configuration("scala-tool")))) forcedScalaModules(so, sv) else Map()) ++
            interProjectDependencies.map(_.moduleVersion),
        projectCache = parentProjectCache,
        mapDependencies = if (typelevel && (configs(Configuration.compile) || configs(Configuration("scala-tool")))) typelevelOrgSwap else None
      )

      val allStartRes = configGraphs.map(configs => configs -> startRes(configs)).toMap

      val key = SbtCoursierCache.ResolutionKey(
        currentProject,
        repositories,
        userEnabledProfiles,
        allStartRes,
        sbtClassifiers
      )

      SbtCoursierCache.default.resolutionOpt(key) match {
        case Some(res) =>
          Def.task(res)
        case None =>
          val t = resTask(
            currentProject,
            fallbackDependencies,
            configGraphs,
            repositories,
            internalRepositories,
            allStartRes
          )

          t.map { res =>
            SbtCoursierCache.default.putResolution(key, res)
            res
          }
      }
    }
  }

}
