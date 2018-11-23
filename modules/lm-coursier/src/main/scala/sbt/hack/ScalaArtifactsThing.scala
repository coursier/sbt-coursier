package sbt.hack

import sbt.librarymanagement.{ModuleID, ScalaArtifacts}

object ScalaArtifactsThing {

  // access private[sbt] method ScalaArtifacts.toolDependencies…
  def toolDependencies(
    org: String,
    version: String,
    isDotty: Boolean = false
  ): Seq[ModuleID] =
    ScalaArtifacts.toolDependencies(org, version, isDotty)

}
