package coursier.sbtcoursier

import java.net.URL

import coursier.ProjectCache
import coursier.core._
import coursier.sbtcoursier.Keys._
import coursier.sbtcoursier.Structure._
import sbt.librarymanagement.{Configuration => _, _}
import sbt.Def
import sbt.Keys._

import scala.collection.mutable

object InputsTasks {

  def coursierFallbackDependenciesTask: Def.Initialize[sbt.Task[Seq[(Module, String, URL, Boolean)]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projects = Structure.allRecursiveInterDependencies(state, projectRef)

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

  def coursierProjectTask: Def.Initialize[sbt.Task[Project]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      // should projectID.configurations be used instead?
      val configurations = ivyConfigurations.in(projectRef).get(state)

      val allDependenciesTask = allDependencies.in(projectRef).get(state)

      val sv = scalaVersion.in(projectRef).get(state)
      val sbv = scalaBinaryVersion.in(projectRef).get(state)

      val exclusions = {

        var anyNonSupportedExclusionRule = false

        val res = excludeDependencies
          .in(projectRef)
          .get(state)
          .flatMap { rule =>
            if (rule.artifact != "*" || rule.configurations.nonEmpty) {
              state.log.warn(s"Unsupported exclusion rule $rule")
              anyNonSupportedExclusionRule = true
              Nil
            } else
              Seq(
                (Organization(rule.organization), ModuleName(FromSbt.sbtCrossVersionName(rule.name, rule.crossVersion, sv, sbv)))
              )
          }
          .toSet

        if (anyNonSupportedExclusionRule)
          state.log.warn("Only supported exclusion rule fields: organization, name")

        res
      }

      val configMap = configurations
        .map(cfg => Configuration(cfg.name) -> cfg.extendsConfigs.map(c => Configuration(c.name)))
        .toMap

      val projId = projectID.in(projectRef).get(state)

      Def.task {

        val allDependencies = allDependenciesTask.value

        val proj = FromSbt.project(
          projId,
          allDependencies,
          configMap,
          sv,
          sbv
        )

        proj.copy(
          dependencies = proj.dependencies.map {
            case (config, dep) =>
              (config, dep.copy(exclusions = dep.exclusions ++ exclusions))
          }
        )
      }
    }

  def coursierInterProjectDependenciesTask: Def.Initialize[sbt.Task[Seq[Project]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projects = Structure.allRecursiveInterDependencies(state, projectRef)

      val t = coursierProject.forAllProjects(state, projects).map(_.values.toVector)

      Def.task(t.value)
    }

  def coursierConfigurationsTask(shadedConfig: Option[(String, Configuration)]) = Def.task {

    val configs0 = ivyConfigurations
      .value
      .map { config =>
        Configuration(config.name) -> config.extendsConfigs.map(c => Configuration(c.name))
      }
      .toMap

    def allExtends(c: Configuration) = {
      // possibly bad complexity
      def helper(current: Set[Configuration]): Set[Configuration] = {
        val newSet = current ++ current.flatMap(configs0.getOrElse(_, Nil))
        if ((newSet -- current).nonEmpty)
          helper(newSet)
        else
          newSet
      }

      helper(Set(c))
    }

    val map = configs0.map {
      case (config, _) =>
        config -> allExtends(config)
    }

    map ++ shadedConfig.toSeq.flatMap {
      case (baseConfig, shadedConfig) =>
        val baseConfig0 = Configuration(baseConfig)
        Seq(
          baseConfig0 -> (map.getOrElse(baseConfig0, Set(baseConfig0)) + shadedConfig),
          shadedConfig -> map.getOrElse(shadedConfig, Set(shadedConfig))
        )
    }
  }

  def ivyGraphsTask = Def.task {

    // probably bad complexity, but that shouldn't matter given the size of the graphs involved...

    val p = coursierProject.value

    final class Wrapper(val set: mutable.HashSet[Configuration]) {
      def ++=(other: Wrapper): this.type = {
        set ++= other.set
        this
      }
    }

    val sets =
      new mutable.HashMap[Configuration, Wrapper] ++= p.configurations.map {
        case (k, l) =>
          val s = new mutable.HashSet[Configuration]
          s ++= l
          s += k
          k -> new Wrapper(s)
      }

    for (k <- p.configurations.keys) {
      val s = sets(k)

      var foundNew = true
      while (foundNew) {
        foundNew = false
        for (other <- s.set.toVector) {
          val otherS = sets(other)
          if (!otherS.eq(s)) {
            s ++= otherS
            sets += other -> s
            foundNew = true
          }
        }
      }
    }

    sets.values.toVector.distinct.map(_.set.toSet)
  }

  def parentProjectCacheTask: Def.Initialize[sbt.Task[Map[Seq[sbt.Resolver], Seq[coursier.ProjectCache]]]] =
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
