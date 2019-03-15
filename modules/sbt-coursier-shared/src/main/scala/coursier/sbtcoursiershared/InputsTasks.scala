package coursier.sbtcoursiershared

import coursier.core._
import coursier.lmcoursier._
import coursier.sbtcoursiershared.SbtCoursierShared.autoImport._
import coursier.sbtcoursiershared.Structure._
import sbt.Def
import sbt.Keys._

import scala.collection.JavaConverters._

object InputsTasks {

  def coursierProjectTask: Def.Initialize[sbt.Task[Project]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val allDependenciesTask = allDependencies.in(projectRef).get(state)

      Def.task {
        Inputs.coursierProject(
          projectID.in(projectRef).get(state),
          allDependenciesTask.value,
          excludeDependencies.in(projectRef).get(state),
          // should projectID.configurations be used instead?
          ivyConfigurations.in(projectRef).get(state),
          scalaVersion.in(projectRef).get(state),
          scalaBinaryVersion.in(projectRef).get(state),
          state.log
        )
      }
    }

  private def moduleFromIvy(id: org.apache.ivy.core.module.id.ModuleRevisionId): Module =
    Module(
      Organization(id.getOrganisation),
      ModuleName(id.getName),
      id.getExtraAttributes
        .asScala
        .map {
          case (k0, v0) => k0.asInstanceOf[String] -> v0.asInstanceOf[String]
        }
        .toMap
    )

  private def dependencyFromIvy(desc: org.apache.ivy.core.module.descriptor.DependencyDescriptor): Seq[(Configuration, Dependency)] = {

    val id = desc.getDependencyRevisionId
    val module = moduleFromIvy(id)
    val exclusions = desc
      .getAllExcludeRules
      .map { rule =>
        // we're ignoring rule.getConfigurations and rule.getMatcher here
        val modId = rule.getId.getModuleId
        // we're ignoring modId.getAttributes here
        (Organization(modId.getOrganisation), ModuleName(modId.getName))
      }
      .toSet

    val configurations = desc
      .getModuleConfigurations
      .toVector
      .flatMap(s => coursier.ivy.IvyXml.mappings(s))

    def dependency(conf: Configuration, attr: Attributes) = Dependency(
      module,
      id.getRevision,
      conf,
      exclusions,
      attr,
      optional = false,
      desc.isTransitive
    )

    val attributes: Configuration => Attributes = {

      val artifacts = desc.getAllDependencyArtifacts

      val m = artifacts.toVector.flatMap { art =>
        val attr = Attributes(Type(art.getType), Classifier.empty)
        art.getConfigurations.map(Configuration(_)).toVector.map { conf =>
          conf -> attr
        }
      }.toMap

      c => m.getOrElse(c, Attributes.empty)
    }

    configurations.map {
      case (from, to) =>
        from -> dependency(to, attributes(to))
    }
  }

  def coursierInterProjectDependenciesTask: Def.Initialize[sbt.Task[Seq[Project]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projectRefs = Structure.allRecursiveInterDependencies(state, projectRef)

      val t = coursierProject.forAllProjectsOpt(state, projectRefs)

      Def.task {
        val projects = t.value.toVector.flatMap {
          case (ref, None) =>
            if (ref.build != projectRef.build)
              state.log.warn(s"Cannot get coursier info for project under ${ref.build}, is sbt-coursier also added to it?")
            Nil
          case (_, Some(p)) =>
            Seq(p)
        }
        val projectModules = projects.map(_.module).toSet

        // this includes org.scala-sbt:global-plugins referenced from meta-builds in particular
        val extraProjects = sbt.Keys.projectDescriptors.value
          .map {
            case (k, v) =>
              moduleFromIvy(k) -> v
          }
          .filter {
            case (module, _) =>
              !projectModules(module)
          }
          .toVector
          .map {
            case (module, v) =>
              val configurations = v
                .getConfigurations
                .map { c =>
                  Configuration(c.getName) -> c.getExtends.map(Configuration(_)).toSeq
                }
                .toMap
              val deps = v.getDependencies.flatMap(dependencyFromIvy)
              Project(
                module,
                v.getModuleRevisionId.getRevision,
                deps,
                configurations,
                None,
                Nil,
                Nil,
                Nil,
                None,
                None,
                None,
                relocated = false,
                None,
                Nil,
                Info.empty
              )
          }

        projects ++ extraProjects
      }
    }

  def coursierFallbackDependenciesTask: Def.Initialize[sbt.Task[Seq[FallbackDependency]]] =
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

  val authenticationByHostTask = Def.taskDyn {

    val useSbtCredentials = coursierUseSbtCredentials.value

    if (useSbtCredentials)
      Def.task {
        val log = streams.value.log

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
  }

}
