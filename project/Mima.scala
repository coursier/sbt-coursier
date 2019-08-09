
import com.typesafe.tools.mima.plugin.MimaPlugin
import com.typesafe.tools.mima.plugin.MimaKeys._
import sbt._
import sbt.Keys._
import sys.process._

object Mima {

  private def stable(ver: String): Boolean =
    ver.exists(c => c != '0' && c != '.') &&
    ver
      .replace("-RC", "-")
      .forall(c => c == '.' || c == '-' || c.isDigit)

  def binaryCompatibilityVersions: Set[String] =
    Seq("git", "tag", "--merged", "HEAD^", "--contains", "736d5c11")
      .!!
      .linesIterator
      .map(_.trim)
      .filter(_.startsWith("v"))
      .map(_.stripPrefix("v"))
      .filter(stable)
      .filter(_ != "2.0.0-RC3-2") // borked release
      .toSet

  def settings: Seq[Setting[_]] = Seq(
    MimaPlugin.autoImport.mimaPreviousArtifacts := {
      binaryCompatibilityVersions.map { ver =>
        (organization.value % moduleName.value % ver).cross(crossVersion.value)
      }
    }
  )

  lazy val lmCoursierFilters = {
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._

      Seq(
        // Removed unused method, shouldn't have been there in the first place
        ProblemFilters.exclude[DirectMissingMethodProblem]("lmcoursier.credentials.DirectCredentials.authentication"),
        // ignore shaded and internal stuff related errors
        (pb: Problem) => pb.matchName.forall(!_.startsWith("lmcoursier.internal."))
      )
    }
  }

  lazy val lmCoursierShadedFilters = {
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._

      Seq(
        // Should have been put under lmcoursier.internal?
        (pb: Problem) => pb.matchName.forall(!_.startsWith("lmcoursier.definitions.ToCoursier."))
      )
    }
  }

}
