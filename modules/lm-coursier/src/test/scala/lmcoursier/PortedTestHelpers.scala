package lmcoursier

import lmcoursier.definitions.{ModuleMatchers, Reconciliation}
import lmcoursier.syntax.ModuleMatchersModule
import sbt.internal.librarymanagement.cross.CrossVersionUtil
import sbt.internal.util.ConsoleLogger
import sbt.librarymanagement._
import sbt.librarymanagement.Configurations._

/**
 * Shared plumbing for the specs ported from the sbt-coursier / sbt-lm-coursier scripted tests.
 *
 * Those scripted tests drove things through sbt keys (conflictManager, versionReconciliation,
 * dependencyOverrides, …). Here we go through the corresponding CoursierConfiguration fields
 * directly, mapping them the way the sbt plugins do.
 */
trait PortedTestHelpers {

  lazy val log = ConsoleLogger()

  // Same set sbt puts in a build, including the internal configurations, so that the
  // Provided / Optional handling below is exercised the way it is from sbt.
  val configurations =
    Vector(Compile, Runtime, Test, Provided, Optional, CompileInternal, RuntimeInternal, TestInternal)

  /** The module the helper below resolves under - inter-project tests have to include it. */
  val selfModule = ModuleID("com.example", "test", "0.1.0-SNAPSHOT")

  def updateReport(
    dependencies: Seq[ModuleID],
    scalaFullVersion: String,
    dependencyOverrides: Seq[ModuleID] = Nil,
    overrideScalaVersion: Boolean = true,
    updateConfiguration: UpdateConfiguration = UpdateConfiguration(),
    configure: CoursierConfiguration => CoursierConfiguration = identity
  ): Either[UnresolvedWarning, UpdateReport] = {

    val scalaBinaryVersion = CrossVersionUtil.binaryScalaVersion(scalaFullVersion)

    val conf = configure(
      CoursierConfiguration()
        .withLog(Some(log))
        .withScalaVersion(Some(scalaFullVersion))
        // that's how the sbt plugins pass dependencyOverrides down
        .withForceVersions(
          Inputs.forceVersions(dependencyOverrides, scalaFullVersion, scalaBinaryVersion).toVector
        )
    )

    val depRes = CoursierDependencyResolution(conf)

    val moduleSetting = ModuleDescriptorConfiguration(selfModule, ModuleInfo("test"))
      .withDependencies(dependencies.toVector)
      .withConfigurations(configurations)
      .withScalaModuleInfo(
        Some(
          ScalaModuleInfo(
            scalaFullVersion = scalaFullVersion,
            scalaBinaryVersion = scalaBinaryVersion,
            configurations = configurations,
            checkExplicit = true,
            filterImplicit = false,
            overrideScalaVersion = overrideScalaVersion
          )
        )
      )

    depRes.update(
      depRes.moduleDescriptor(moduleSetting),
      updateConfiguration,
      UnresolvedWarningConfiguration(),
      log
    )
  }

  def orThrow(res: Either[UnresolvedWarning, UpdateReport]): UpdateReport =
    res.fold(w => throw w.resolveException, identity)

  // coursier puts ANSI escape codes in some of its error messages
  def plainText(message: String): String =
    fansi.Str(message, errorMode = fansi.ErrorMode.Sanitize).plainText

  def modulesIn(report: UpdateReport, config: Configuration): Seq[ModuleID] =
    report
      .configuration(config)
      .getOrElse {
        val found = report.configurations.map(_.configuration.name)
        sys.error(s"$config configuration not found in update report (got $found)")
      }
      .modules
      .map(_.module)

  // that's how the sbt plugins map the versionReconciliation key
  def reconciliation(mod: ModuleID, scalaFullVersion: String): (ModuleMatchers, Reconciliation) = {
    val sbv = CrossVersionUtil.binaryScalaVersion(scalaFullVersion)
    val rec = Reconciliation(mod.revision).getOrElse {
      sys.error(s"Unrecognized reconciliation: '${mod.revision}'")
    }
    val (mod0, _) = FromSbt.moduleVersion(mod, scalaFullVersion, sbv)
    ModuleMatchers.only(mod0) -> rec
  }

  def versionsOf(report: UpdateReport, config: Configuration, org: String, namePrefix: String): Set[String] =
    modulesIn(report, config).collect {
      case m if m.organization == org && m.name.startsWith(namePrefix) => m.revision
    }.toSet

}
