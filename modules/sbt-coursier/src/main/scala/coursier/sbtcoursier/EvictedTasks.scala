package coursier.sbtcoursier

import coursier.core.{Configuration, Dependency}
import coursier.sbtcoursier.CoursierPlugin.autoImport.coursierResolutions
import sbt.{Def, EvictionWarning, Task}
import sbt.Keys._

object EvictedTasks {

  def evictedTask(): Def.Initialize[Task[EvictionWarning]] =
    Def.task {
      val log = streams.value.log

      val resolution = coursierResolutions.value
        .filter(_._1.contains(Configuration("compile")))
        .head
        ._2

      def isEvicted(dependency: Dependency): Boolean =
        resolution.finalDependenciesCache.keys.exists { finalDependency =>
          dependency.module == finalDependency.module && dependency.version != finalDependency.version
        }

      def findAllVersions(dependency: Dependency): Set[String] =
        resolution.finalDependenciesCache.keys
          .filter(_.module == dependency.module)
          .map(_.version)
          .toSet

      def findLatestVersion(dependency: Dependency): String =
        resolution.dependencies
          .find(_.module == dependency.module)
          .map(_.version)
          .getOrElse("(unknown)") // this should never happen actually

      def findReverseDeps(dependency: Dependency): Seq[(Dependency, String)] =
        resolution.reverseDependencies
          .filterKeys(_.module == dependency.module)
          .toSeq
          .flatMap { t =>
            t._2.map(d => (d, d.version + " => " + t._1.version))
          }
          .distinct

      // evicted deps and all their versions
      val evictedDeps: Map[Dependency, Set[String]] =
        resolution.rootDependencies
          .filter(isEvicted)
          .map { rootDep =>
            (rootDep, findAllVersions(rootDep))
          }
          .toMap

      // TODO Returns 0 conflicts, investigate
      /*
      val conflicts: Seq[Conflict] = Conflict(resolution)
      if(conflicts.nonEmpty) {
        log.warn(
          s"Found version conflict(s) in library dependencies; some are suspected to be binary incompatible:")
        conflicts.foreach { conflict =>
          log.warn(s"""Using ${conflict.module}:${conflict.version} over ${conflict.wantedVersion}
                      |(wanted by ${conflict.dependeeModule}:${conflict.dependeeVersion})""".stripMargin)
        }
      }
       */

      if (evictedDeps.nonEmpty) {
        log.warn(
          s"Found version conflict(s) in library dependencies; some are suspected to be binary incompatible:")
        evictedDeps.foreach { evictedDep =>
          log.warn(s"  *: ${evictedDep._1.module}:${findLatestVersion(
            evictedDep._1)} (${evictedDep._2.mkString(", ")})")
          findReverseDeps(evictedDep._1).foreach { reverse =>
            log.warn(
              s"      +- ${reverse._1.module}:${reverse._1.version} (depends on ${reverse._2})")
          }
        }
      }

      EvictionWarning(
        dependencyResolution.value.moduleDescriptor(
          projectID.value,
          Vector(),
          scalaModuleInfo.value
        ),
        (evictionWarningOptions in evicted).value,
        update.value,
        log
      )
    }

}
