package coursier.lmcoursier

import sbt.librarymanagement._

final case class CoursierModuleDescriptor(
  descriptor: ModuleDescriptorConfiguration,
  conf: CoursierConfiguration
) extends ModuleDescriptor {

  def directDependencies: Vector[ModuleID] =
    descriptor.dependencies

  def scalaModuleInfo: Option[ScalaModuleInfo] =
    descriptor.scalaModuleInfo

  def moduleSettings: CoursierModuleSettings =
    CoursierModuleSettings()

  lazy val extraInputHash: Long =
    conf.##
}
