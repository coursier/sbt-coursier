package coursier.sbtcoursier

import java.io.File
import java.net.URL

import coursier.{Cache, CachePolicy, FallbackDependenciesRepository, ProjectCache, Resolution, moduleNameString}
import coursier.core._
import coursier.extra.Typelevel
import sbt.util.Logger

import scala.concurrent.duration.Duration

final case class ResolutionParams(
  currentProject: Project,
  fallbackDependencies: Seq[(Module, String, URL, Boolean)],
  configGraphs: Seq[Set[Configuration]],
  autoScalaLib: Boolean,
  mainRepositories: Seq[Repository],
  parentProjectCache: ProjectCache,
  interProjectDependencies: Seq[Project],
  internalRepositories: Seq[Repository],
  userEnabledProfiles: Set[String],
  userForceVersions: Map[Module, String],
  typelevel: Boolean,
  so: Organization,
  sv: String,
  sbtClassifiers: Boolean,
  parallelDownloads: Int,
  projectName: String,
  maxIterations: Int,
  createLogger: () => Cache.Logger,
  cache: File,
  cachePolicies: Seq[CachePolicy],
  ttl: Option[Duration],
  checksums: Seq[Option[String]]
) {

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

  val repositories =
    internalRepositories ++
      mainRepositories ++
      fallbackDependenciesRepositories

  private val noOptionalFilter: Option[Dependency => Boolean] = Some(dep => !dep.optional)
  private val typelevelOrgSwap: Option[Dependency => Dependency] = Some(Typelevel.swap(_))

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

  private def startRes(configs: Set[Configuration]) = Resolution(
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

  lazy val allStartRes = configGraphs.map(configs => configs -> startRes(configs)).toMap

  lazy val resolutionKey = SbtCoursierCache.ResolutionKey(
    currentProject,
    repositories,
    userEnabledProfiles,
    allStartRes,
    sbtClassifiers
  )

}
