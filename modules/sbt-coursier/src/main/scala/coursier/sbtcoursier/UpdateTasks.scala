package coursier.sbtcoursier

import coursier.core._
import lmcoursier.definitions.ToCoursier
import lmcoursier.internal.{SbtBootJars, SbtCoursierCache, UpdateParams, UpdateRun}
import coursier.sbtcoursier.Keys._
import coursier.sbtcoursiershared.SbtCoursierShared.autoImport._
import sbt.Def
import sbt.Keys._
import sbt.librarymanagement.UpdateReport

object UpdateTasks {

  def updateTask(
    shadedConfigOpt: Option[(String, Configuration)],
    withClassifiers: Boolean,
    sbtClassifiers: Boolean = false,
    includeSignatures: Boolean = false
  ): Def.Initialize[sbt.Task[UpdateReport]] = {

    val currentProjectTask =
      if (sbtClassifiers)
        Def.task {
          val sv = scalaVersion.value
          val sbv = scalaBinaryVersion.value
          SbtCoursierFromSbt.sbtClassifiersProject(coursierSbtClassifiersModule.value, sv, sbv)
        }
      else
        Def.task {
          val proj = coursierProject.value
          val publications = coursierPublications.value
          proj.withPublications(publications)
        }

    val resTask =
      if (withClassifiers && sbtClassifiers)
        Def.task {
          val cm = coursierSbtClassifiersModule.value
          val classifiersRes = coursierSbtClassifiersResolution.value
          Map(cm.configurations.map(c => Configuration(c.name)).toSet -> classifiersRes)
        }
      else
        Def.task(coursierResolutions.value)

    // we should be able to call .value on that one here, its conditions don't originate from other tasks
    val artifactFilesOrErrors0Task =
      if (withClassifiers) {
        if (sbtClassifiers)
          Keys.coursierSbtClassifiersArtifacts
        else
          Keys.coursierClassifiersArtifacts
      } else if (includeSignatures)
        Keys.coursierSignedArtifacts
      else
        Keys.coursierArtifacts

    val configsTask: sbt.Def.Initialize[sbt.Task[Map[Configuration, Set[Configuration]]]] =
      if (withClassifiers && sbtClassifiers)
        Def.task {
          val cm = coursierSbtClassifiersModule.value
          cm.configurations.map(c => Configuration(c.name) -> Set(Configuration(c.name))).toMap
        }
      else
        Def.task {
          val configs0 = coursierConfigurations.value

          shadedConfigOpt.fold(configs0) {
            case (baseConfig, shadedConfig) =>
              val baseConfig0 = Configuration(baseConfig)
              (configs0 - shadedConfig) + (
                baseConfig0 -> (configs0.getOrElse(baseConfig0, Set()) - shadedConfig)
              )
          }
        }

    val classifiersTask: sbt.Def.Initialize[sbt.Task[Option[Seq[Classifier]]]] =
      if (withClassifiers) {
        if (sbtClassifiers)
          Def.task {
            val cm = coursierSbtClassifiersModule.value
            Some(cm.classifiers.map(Classifier(_)))
          }
        else
          Def.task(Some(transitiveClassifiers.value.map(Classifier(_))))
      } else
        Def.task(None)

    Def.taskDyn {

      val so = Organization(scalaOrganization.value)
      val internalSbtScalaProvider = appConfiguration.value.provider.scalaProvider
      val sbtBootJarOverrides = SbtBootJars(
        so, // this seems plain wrong - this assumes that the scala org of the project is the same
        // as the one that started SBT. This will scrap the scala org specific JARs by the ones
        // that booted SBT, even if the latter come from the standard org.scala-lang org.
        // But SBT itself does it this way, and not doing so may make two different versions
        // of the scala JARs land in the classpath...
        internalSbtScalaProvider.version(),
        internalSbtScalaProvider.jars()
      )

      val log = streams.value.log

      val verbosityLevel = coursierVerbosity.value
      val p = ToCoursier.project(currentProjectTask.value)
      val dependencies = p.dependencies
      val res = resTask.value

      val key = SbtCoursierCache.ReportKey(
        dependencies,
        res,
        withClassifiers,
        sbtClassifiers,
        includeSignatures
      )

      val interProjectDependencies = coursierInterProjectDependencies.value.map(ToCoursier.project)

      SbtCoursierCache.default.reportOpt(key) match {
        case Some(report) =>
          Def.task(report)
        case None =>
          Def.task {

            val artifactFilesOrErrors0 = artifactFilesOrErrors0Task.value
            val classifiers = classifiersTask.value
            val configs = configsTask.value

            val params = UpdateParams(
              (p.module, p.version),
              shadedConfigOpt,
              artifactFilesOrErrors0,
              classifiers,
              configs,
              dependencies,
              interProjectDependencies,
              res,
              includeSignatures,
              sbtBootJarOverrides
            )

            val rep = UpdateRun.update(params, verbosityLevel, log)
            SbtCoursierCache.default.putReport(key, rep)
            rep
          }
      }
    }
  }

}
