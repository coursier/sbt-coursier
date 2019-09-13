package lmcoursier.internal

import java.io.File

import coursier.cache.{CacheLogger, FileCache}
import coursier.ProjectCache
import coursier.core._
import coursier.params.rule.Strict
import lmcoursier.FallbackDependency
import lmcoursier.definitions.ToCoursier
import coursier.util.Task

// private[coursier]
final case class ResolutionParams(
  dependencies: Seq[(Configuration, Dependency)],
  fallbackDependencies: Seq[FallbackDependency],
  configGraphs: Seq[Set[Configuration]],
  autoScalaLibOpt: Option[(Organization, String)],
  mainRepositories: Seq[Repository],
  parentProjectCache: ProjectCache,
  interProjectDependencies: Seq[Project],
  internalRepositories: Seq[Repository],
  sbtClassifiers: Boolean,
  projectName: String,
  loggerOpt: Option[CacheLogger],
  cache: coursier.cache.FileCache[Task],
  parallel: Int,
  params: coursier.params.ResolutionParams,
  strictOpt: Option[Strict]
) {

  val fallbackDependenciesRepositories =
    if (fallbackDependencies.isEmpty)
      Nil
    else {
      val map = fallbackDependencies
        .map { dep =>
          (ToCoursier.module(dep.module), dep.version) -> ((dep.url, dep.changing))
        }
        .toMap

      Seq(
        TemporaryInMemoryRepository(map, cache)
      )
    }

  lazy val resolutionKey = {
    val cleanCache = cache
      .withPool(null)
      .withLogger(null)
      .withSync[Task](null)
    SbtCoursierCache.ResolutionKey(
      dependencies,
      internalRepositories,
      mainRepositories,
      fallbackDependenciesRepositories,
      copy(
        parentProjectCache = Map.empty,
        loggerOpt = None,
        parallel = 0,
        cache = cleanCache
      ),
      ResolutionParams.cacheKey(cleanCache),
      sbtClassifiers
    )
  }

  override lazy val hashCode =
    ResolutionParams.unapply(this).get.##

}

// private[coursier]
object ResolutionParams {

  private lazy val m = {
    val cls = classOf[FileCache[Task]]
    //cls.getDeclaredMethods.foreach(println)
    val m = cls.getDeclaredMethod("params")
    m.setAccessible(true)
    m
  }

  // temporary, until we can use https://github.com/coursier/coursier/pull/1090
  private def cacheKey(cache: FileCache[Task]): Object =
    m.invoke(cache)

  def defaultIvyProperties(ivyHomeOpt: Option[File]): Map[String, String] = {

    val ivyHome = sys.props
      .get("ivy.home")
      .orElse(ivyHomeOpt.map(_.getAbsoluteFile.toURI.getPath))
      .getOrElse(new File(sys.props("user.home")).toURI.getPath + ".ivy2")

    val sbtIvyHome = sys.props.getOrElse(
      "sbt.ivy.home",
      ivyHome
    )

    Map(
      "ivy.home" -> ivyHome,
      "sbt.ivy.home" -> sbtIvyHome
    ) ++ sys.props
  }

}
