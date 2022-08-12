package coursier.sbtcoursiershared

import java.net.URL
import lmcoursier.definitions.{
  Classifier => CClassifier,
  Configuration => CConfiguration,
  Dependency => CDependency,
  Extension => CExtension,
  Info => CInfo,
  Module => CModule,
  ModuleName => CModuleName,
  Organization => COrganization,
  Project => CProject,
  Publication => CPublication,
  Strict => CStrict,
  Type => CType
}
import lmcoursier.{FallbackDependency, FromSbt, Inputs}
import coursier.sbtcoursiershared.SbtCoursierShared.autoImport._
import coursier.sbtcoursiershared.Structure._
import lmcoursier.credentials.DirectCredentials
import sbt._
import sbt.Keys._
import sbt.librarymanagement.{ConflictManager, InclExclRule, ModuleID}
import sbt.util.Logger

import scala.collection.JavaConverters._
import scala.language.reflectiveCalls

object InputsTasks {

  lazy val actualExcludeDependencies: SettingKey[scala.Seq[InclExclRule]] =
    try {
      sbt.Keys
        .asInstanceOf[{ def allExcludeDependencies: SettingKey[scala.Seq[InclExclRule]] }]
        .allExcludeDependencies
    } catch {
      case _: NoSuchMethodException =>
        excludeDependencies
    }

  private def coursierProject0(
    projId: ModuleID,
    dependencies: Seq[ModuleID],
    configurations: Seq[sbt.librarymanagement.Configuration],
    sv: String,
    sbv: String,
    auOpt: Option[URL],
    log: Logger
  ): CProject = {

    val configMap = Inputs.configExtendsSeq(configurations).toMap

    val proj0 = FromSbt.project(
      projId,
      dependencies,
      configMap,
      sv,
      sbv
    )
    auOpt match {
      case Some(au) =>
        val props = proj0.properties :+ ("info.apiURL" -> au.toString)
        proj0.withProperties(props)
      case _ => proj0
    }
  }

  private[sbtcoursiershared] def coursierProjectTask: Def.Initialize[sbt.Task[CProject]] =
    Def.task {
      coursierProject0(
        projectID.value,
        allDependencies.value,
        // should projectID.configurations be used instead?
        ivyConfigurations.value,
        scalaVersion.value,
        scalaBinaryVersion.value,
        apiURL.value,
        streams.value.log
      )
    }

  private def moduleFromIvy(id: org.apache.ivy.core.module.id.ModuleRevisionId): CModule =
    CModule(
      COrganization(id.getOrganisation),
      CModuleName(id.getName),
      id.getExtraAttributes.asScala.map {
        case (k0, v0) => k0.asInstanceOf[String] -> v0.asInstanceOf[String]
      }.toMap
    )

  private def dependencyFromIvy(
      desc: org.apache.ivy.core.module.descriptor.DependencyDescriptor
  ): Seq[(CConfiguration, CDependency)] = {

    val id = desc.getDependencyRevisionId
    val module = moduleFromIvy(id)
    val exclusions = desc.getAllExcludeRules.map { rule =>
      // we're ignoring rule.getConfigurations and rule.getMatcher here
      val modId = rule.getId.getModuleId
      // we're ignoring modId.getAttributes here
      (COrganization(modId.getOrganisation), CModuleName(modId.getName))
    }.toSet

    val configurations = desc.getModuleConfigurations.toVector
      .flatMap(Inputs.ivyXmlMappings)

    def dependency(conf: CConfiguration, pub: CPublication) = CDependency(
      module,
      id.getRevision,
      conf,
      exclusions,
      pub,
      optional = false,
      desc.isTransitive
    )

    val publications: CConfiguration => CPublication = {

      val artifacts = desc.getAllDependencyArtifacts

      val m = artifacts.toVector.flatMap { art =>
        val pub = CPublication(art.getName, CType(art.getType), CExtension(art.getExt), CClassifier(""))
        art.getConfigurations.map(CConfiguration(_)).toVector.map { conf =>
          conf -> pub
        }
      }.toMap

      c => m.getOrElse(c, CPublication("", CType(""), CExtension(""), CClassifier("")))
    }

    configurations.map {
      case (from, to) =>
        from -> dependency(to, publications(to))
    }
  }

  private[sbtcoursiershared] def coursierInterProjectDependenciesTask: Def.Initialize[sbt.Task[Seq[CProject]]] =
    Def.taskDyn {

      val state = sbt.Keys.state.value
      val projectRef = sbt.Keys.thisProjectRef.value

      val projectRefs = transitiveInterDependencies(state, projectRef)

      Def.task {
        coursierProject.all(ScopeFilter(inProjects(projectRefs: _*))).value
      }
    }

  private[sbtcoursiershared] def coursierExtraProjectsTask: Def.Initialize[sbt.Task[Seq[CProject]]] =
    Def.task {
      val projects = coursierInterProjectDependencies.value
      val projectModules = projects.map(_.module).toSet

      // this includes org.scala-sbt:global-plugins referenced from meta-builds in particular
      sbt.Keys.projectDescriptors.value
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
                CConfiguration(c.getName) -> c.getExtends.map(CConfiguration(_)).toSeq
              }
              .toMap
            val deps = v.getDependencies.flatMap(dependencyFromIvy)
            CProject(
              module,
              v.getModuleRevisionId.getRevision,
              deps,
              configurations,
              Nil,
              None,
              Nil,
              CInfo("", "", Nil, Nil, None)
            )
        }
    }

  private[sbtcoursiershared] def coursierFallbackDependenciesTask: Def.Initialize[sbt.Task[Seq[FallbackDependency]]] =
    Def.taskDyn {
      val s = state.value
      val projectRef = thisProjectRef.value
      val projects = transitiveInterDependencies(s, projectRef)

      Def.task {
        val allDeps =
          allDependencies.all(ScopeFilter(inProjects(projectRef +: projects: _*))).value.flatten

        FromSbt.fallbackDependencies(
          allDeps,
          scalaVersion.value,
          scalaBinaryVersion.value
        )
      }
    }

  val credentialsTask = Def.taskDyn {

    val useSbtCredentials = coursierUseSbtCredentials.value

    val fromSbt =
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
              DirectCredentials()
                .withHost(c.host)
                .withUsername(c.userName)
                .withPassword(c.passwd)
                .withRealm(Some(c.realm).filter(_.nonEmpty))
                .withHttpsOnly(false)
                .withMatchHost(true)
            }
        }
      else
        Def.task(Seq.empty[DirectCredentials])

    Def.task {
      fromSbt.value ++ coursierExtraCredentials.value
    }
  }


  def strictTask = Def.task {
    val cm = conflictManager.value
    val log = streams.value.log

    cm.name match {
      case ConflictManager.latestRevision.name =>
        None
      case ConflictManager.strict.name =>
        val strict = CStrict()
          .withInclude(Set((cm.organization, cm.module)))
        Some(strict)
      case other =>
        log.warn(s"Unsupported conflict manager $other")
        None
    }
  }


}
