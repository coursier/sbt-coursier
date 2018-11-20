package coursier.sbtcoursier

import java.net.URL

import coursier.ProjectCache
import coursier.core._
import coursier.lmcoursier._
import coursier.sbtcoursier.Keys._
import coursier.sbtcoursiershared.SbtCoursierShared.autoImport._
import coursier.sbtcoursiershared.Structure._
import sbt.librarymanagement.{Configuration => _, _}
import sbt.Def
import sbt.Keys._

object InputsTasks {

  def coursierFallbackDependenciesTask: Def.Initialize[sbt.Task[Seq[(Module, String, URL, Boolean)]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projects = allRecursiveInterDependencies(state, projectRef)

      val allDependenciesTask = allDependencies
        .forAllProjects(state, projectRef +: projects)
        .map(_.values.toVector.flatten)

      Def.task {
        val allDependencies = allDependenciesTask.value

        FromSbt.fallbackDependencies(
          allDependencies,
          scalaVersion.in(projectRef).get(state),
          scalaBinaryVersion.in(projectRef).get(state)
        )
      }
    }

  def coursierConfigurationsTask(
    shadedConfig: Option[(String, Configuration)]
  ): Def.Initialize[sbt.Task[Map[Configuration, Set[Configuration]]]] =
    Def.task {
      Inputs.coursierConfigurations(ivyConfigurations.value, shadedConfig)
    }

  def ivyGraphsTask: Def.Initialize[sbt.Task[Seq[Set[Configuration]]]] =
    Def.task {
      val p = coursierProject.value
      Inputs.ivyGraphs(p.configurations)
    }

  def parentProjectCacheTask: Def.Initialize[sbt.Task[Map[Seq[sbt.librarymanagement.Resolver], Seq[coursier.ProjectCache]]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projectDeps = structure(state).allProjects
        .find(_.id == projectRef.project)
        .map(_.dependencies.map(_.project.project).toSet)
        .getOrElse(Set.empty)

      val projects = structure(state).allProjectRefs.filter(p => projectDeps(p.project))

      val t =
        for {
          m <- coursierRecursiveResolvers.forAllProjects(state, projects)
          n <- coursierResolutions.forAllProjects(state, m.keys.toSeq)
        } yield
          n.foldLeft(Map.empty[Seq[Resolver], Seq[ProjectCache]]) {
            case (caches, (ref, resolutions)) =>
              val mainResOpt = resolutions.collectFirst {
                case (k, v) if k(Configuration.compile) => v
              }

              val r = for {
                resolvers <- m.get(ref)
                resolution <- mainResOpt
              } yield
                caches.updated(resolvers, resolution.projectCache +: caches.getOrElse(resolvers, Seq.empty))

              r.getOrElse(caches)
          }

      Def.task(t.value)
    }

}
