package coursier.sbtcoursier

import coursier.sbtcoursier.Keys._
import coursier.sbtcoursier.Structure._
import sbt.{Classpaths, Def, Resolver}
import sbt.Keys._

object RepositoriesTasks {

  private val slowReposBase = Seq(
    "https://repo.typesafe.com/",
    "https://repo.scala-sbt.org/",
    "http://repo.typesafe.com/",
    "http://repo.scala-sbt.org/"
  )

  private val fastReposBase = Seq(
    "http://repo1.maven.org/",
    "https://repo1.maven.org/"
  )

  def coursierResolversTask: Def.Initialize[sbt.Task[Seq[Resolver]]] = Def.taskDyn {

    def url(res: Resolver): Option[String] =
      res match {
        case m: sbt.librarymanagement.MavenRepository =>
          Some(m.root)
        case u: sbt.URLRepository =>
          u.patterns.artifactPatterns.headOption
            .orElse(u.patterns.ivyPatterns.headOption)
        case _ =>
          None
      }

    def fastRepo(res: Resolver): Boolean =
      url(res).exists(u => fastReposBase.exists(u.startsWith))
    def slowRepo(res: Resolver): Boolean =
      url(res).exists(u => slowReposBase.exists(u.startsWith))

    val bootResOpt = bootResolvers.value
    val overrideFlag = overrideBuildResolvers.value

    val resultTask = bootResOpt.filter(_ => overrideFlag) match {
      case Some(r) => Def.task(r)
      case None =>
        Def.taskDyn {
          val extRes = externalResolvers.value
          val isSbtPlugin = sbtPlugin.value
          if (isSbtPlugin)
            Def.task {
              Seq(
                sbtResolver.value,
                Classpaths.sbtPluginReleases
              ) ++ extRes
            }
          else
            Def.task(extRes)
        }
    }

    Def.task {
      val result = resultTask.value
      val reorderResolvers = coursierReorderResolvers.value
      val keepPreloaded = coursierKeepPreloaded.value

      val result0 =
        if (reorderResolvers && result.exists(fastRepo) && result.exists(slowRepo)) {
          val (slow, other) = result.partition(slowRepo)
          other ++ slow
        } else
          result

      if (keepPreloaded)
        result0
      else
        result0.filter { r =>
          !r.name.startsWith("local-preloaded")
        }
    }
  }

  def coursierRecursiveResolversTask: Def.Initialize[sbt.Task[Seq[Resolver]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projects = Structure.allRecursiveInterDependencies(state, projectRef)

      val t = coursierResolvers
        .forAllProjects(state, projectRef +: projects)
        .map(_.values.toVector.flatten)

      Def.task(t.value)
    }

}
