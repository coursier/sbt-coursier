import sbt._
import Keys._

import java.io.File

object SbtScriptedIT extends AutoPlugin {

  object autoImport {
    val scriptedTestSbtRepo = settingKey[String]("SBT repository to be used in scripted tests")
    val scriptedTestSbtRef = settingKey[String]("SBT branch to be used in scripted tests")
    val scriptedSbtVersion = settingKey[String]("SBT version to be published locally for IT tests")
  }

  import autoImport._
  override def requires = ScriptedPlugin

  override def trigger = noTrigger

  override lazy val globalSettings = Seq(
    scriptedTestSbtRepo := "https://github.com/alexarchambault/sbt.git",
    scriptedTestSbtRef := "origin/topic/lm-coursier",
    scriptedSbtVersion := s"""${sbtVersion.value}-lm-coursier-SNAPSHOT"""
  )

  private def cloneSbt(targetDir: File, repo: String, ref: String) = {
    import org.eclipse.jgit.api._

    if (!targetDir.exists) {
      IO.createDirectory(targetDir)

      new CloneCommand()
        .setDirectory(targetDir)
        .setURI(repo)
        .call()

      val git = Git.open(targetDir)

      git.checkout().setName(ref).call()
    }
  }

  private def publishLocalSbt(
      targetDir: File,
      lmVersion: String,
      lmGroupID: String,
      lmArtifactID: String,
      version: String) = {
    import sys.process._
    Process(
      Seq(
        "sbt",
        "-J-Xms2048m",
        "-J-Xmx2048m",
        "-J-XX:ReservedCodeCacheSize=256m",
        "-J-XX:MaxMetaspaceSize=512m",
        s"""-Dsbt.build.lm.version=$lmVersion""",
        s"""-Dsbt.build.lm.organization=$lmGroupID""",
        s"""-Dsbt.build.lm.moduleName=$lmArtifactID""",
        s"""set ThisBuild / version := "$version"""",
        "clean",
        "publishLocal"
      ),
      Some(targetDir)
    ) !
  }

  import sbt.ScriptedPlugin.autoImport._

  override lazy val projectSettings = Seq(
    scriptedSbtVersion := "1.2.3-lm-coursier-SNAPSHOT",
    scriptedSbt := scriptedSbtVersion.value,
    scriptedTests := {
      val targetDir = target.value / "sbt"

      if (!targetDir.exists) {
        cloneSbt(targetDir, scriptedTestSbtRepo.value, scriptedTestSbtRef.value)

        publishLocalSbt(
          targetDir,
          version.value,
          organization.value,
          moduleName.value,
          scriptedSbtVersion.value
        )
      }

      scriptedTests.value
    }
  )
}
