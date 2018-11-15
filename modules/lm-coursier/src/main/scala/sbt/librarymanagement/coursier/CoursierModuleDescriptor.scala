package sbt.librarymanagement.coursier

import sbt.librarymanagement._

case class CoursierModuleDescriptor(
    directDependencies: Vector[ModuleID],
    scalaModuleInfo: Option[ScalaModuleInfo],
    moduleSettings: ModuleSettings,
    configurations: Seq[String],
    extraInputHash: Long
) extends ModuleDescriptor
