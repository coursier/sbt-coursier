package lmcoursier

import coursier.error.conflict.StrictRule
import lmcoursier.definitions.Strict
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import sbt.librarymanagement._
import sbt.librarymanagement.Configurations.{CompileInternal, RuntimeInternal, TestInternal}
import sbt.librarymanagement.syntax._

/** Batches 1 and 2 of the scripted-test port. See [[PortedTestHelpers]]. */
final class ScriptedPortSpec extends AnyPropSpec with Matchers with PortedTestHelpers {

  // from shared-2/version-interval
  property("version intervals are resolved to actual versions") {

    val report = orThrow {
      updateReport(
        Seq("org.json4s" %% "json4s-native" % "[3.3.0,3.5.0)" % Compile),
        scalaFullVersion = "2.12.20"
      )
    }

    val modules = modulesIn(report, Compile)

    modules should not be empty
    modules.map(_.name) should contain("json4s-native_2.12")

    val withIntervals = modules.filter { m =>
      val v = m.revision
      v.contains("[") || v.contains("]") || v.contains("(") || v.contains(")")
    }

    withClue(s"unexpected intervals in ${withIntervals.map(m => s"${m.organization}:${m.name}:${m.revision}")}") {
      withIntervals shouldBe empty
    }
  }

  // from shared-2/strict-conflict-manager
  private val strictConflictManager = Strict().withInclude(Set(("*", "*")))

  private val conflictingDeps = Seq(
    "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M4" % Compile,
    "com.chuusai" %% "shapeless" % "2.3.3" % Compile
  )

  property("strict conflict manager fails on a version conflict") {

    // the rule violation comes out as an exception rather than as a Left
    val thrown = the[StrictRule] thrownBy {
      orThrow {
        updateReport(
          conflictingDeps,
          scalaFullVersion = "2.12.8",
          configure = _.withStrict(Some(strictConflictManager))
        )
      }
    }

    plainText(thrown.getMessage) should include(
      "com.chuusai:shapeless_2.12:2.3.3 (2.3.2 wanted)"
    )
  }

  property("strict conflict manager passes if the conflicting version is forced") {

    // strict cm should be fine if we force the conflicting module version
    val res = updateReport(
      conflictingDeps,
      scalaFullVersion = "2.12.8",
      dependencyOverrides = Seq("com.chuusai" %% "shapeless" % "2.3.3"),
      configure = _.withStrict(Some(strictConflictManager))
    )

    val _ = orThrow(res)
  }

  property("strict reconciliation on a module with no conflict") {

    val res = updateReport(
      Seq(
        "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0" % Compile,
        "com.chuusai" %% "shapeless" % "2.3.2" % Compile
      ),
      scalaFullVersion = "2.12.11",
      configure = _.withReconciliation(
        Vector(
          reconciliation("com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "strict", "2.12.11")
        )
      )
    )

    val _ = orThrow(res)
  }

  // from sbt-lm-coursier/evicted
  // examples adapted from https://github.com/coursier/sbt-coursier/pull/75#issuecomment-497128870
  private def hasEvictions(report: UpdateReport): Boolean = {
    val compileReport = report
      .configurations
      .find(_.configuration.name == "compile")
      .getOrElse {
        sys.error("compile report not found")
      }
    compileReport.details.exists(_.modules.exists(_.evicted))
  }

  property("evictions are reported (cats)") {

    val report = orThrow {
      updateReport(
        Seq(
          "org.typelevel" %% "cats-effect" % "1.3.1" % Compile,
          "org.typelevel" %% "cats-core" % "1.5.0" % Compile
        ),
        scalaFullVersion = "2.12.8"
      )
    }

    hasEvictions(report) shouldBe true
  }

  property("evictions are reported (slf4j)") {

    val report = orThrow {
      updateReport(
        Seq(
          "org.slf4s" %% "slf4s-api" % "1.7.25" % Compile, // depends on org.slf4j:slf4j-api:1.7.25
          "ch.qos.logback" % "logback-classic" % "1.1.2" % Compile // depends on org.slf4j:slf4j-api:1.7.6
        ),
        scalaFullVersion = "2.12.8"
      )
    }

    hasEvictions(report) shouldBe true
  }

  property("no eviction reported when the version is overridden") {

    val report = orThrow {
      updateReport(
        Seq(
          "org.slf4s" %% "slf4s-api" % "1.7.25" % Compile,
          "ch.qos.logback" % "logback-classic" % "1.1.2" % Compile
        ),
        scalaFullVersion = "2.12.8",
        dependencyOverrides = Seq("org.slf4j" % "slf4j-api" % "1.7.30")
      )
    }

    hasEvictions(report) shouldBe false
  }

  // from shared-2/provided
  property("provided dependencies land in the internal configurations only") {

    val report = orThrow {
      updateReport(
        Seq(
          "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M5" % Compile,
          "com.chuusai" %% "shapeless" % "2.3.3" % Provided,
          "javax.servlet" % "servlet-api" % "2.5" % Provided
        ),
        scalaFullVersion = "2.12.11"
      )
    }

    def checkShapeless(config: Configuration, expected: Option[String]): Unit =
      withClue(s"shapeless versions in $config") {
        versionsOf(report, config, "com.chuusai", "shapeless") shouldBe expected.toSet
      }

    def checkServlet(config: Configuration, expected: Option[String]): Unit =
      withClue(s"servlet-api versions in $config") {
        versionsOf(report, config, "javax.servlet", "servlet-api") shouldBe expected.toSet
      }

    checkShapeless(Compile, Some("2.3.2"))
    checkShapeless(CompileInternal, Some("2.3.3"))
    checkShapeless(Test, Some("2.3.2"))
    checkShapeless(TestInternal, Some("2.3.3"))
    checkShapeless(Provided, Some("2.3.3"))
    checkShapeless(Runtime, Some("2.3.2"))
    checkShapeless(RuntimeInternal, Some("2.3.2"))

    checkServlet(Compile, None)
    checkServlet(CompileInternal, Some("2.5"))
    checkServlet(Test, None)
    checkServlet(TestInternal, Some("2.5"))
    checkServlet(Provided, Some("2.5"))
    checkServlet(Runtime, None)
    checkServlet(RuntimeInternal, None)
  }

  // from shared-2/version-reconciliation
  property("strict reconciliation for every module fails on a version conflict") {

    a[StrictRule] should be thrownBy {
      orThrow {
        updateReport(
          conflictingDeps,
          scalaFullVersion = "2.12.8",
          configure = _.withReconciliation(Vector(reconciliation("*" % "*" % "strict", "2.12.8")))
        )
      }
    }
  }

  property("strict reconciliation passes if the conflicting version is forced") {

    val res = updateReport(
      conflictingDeps,
      scalaFullVersion = "2.12.8",
      dependencyOverrides = Seq("com.chuusai" %% "shapeless" % "2.3.3"),
      configure = _.withReconciliation(Vector(reconciliation("*" % "*" % "strict", "2.12.8")))
    )

    val _ = orThrow(res)
  }

  // from shared-2/semver-reconciliation
  private def argonautShapeless(argonautVersion: String) = Seq(
    "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M11" % Compile,
    "io.argonaut" %% "argonaut" % argonautVersion % Compile
  )

  private def semverAll = Vector(reconciliation("*" % "*" % "semver", "2.11.12"))
  private def strictAll = Vector(reconciliation("*" % "*" % "strict", "2.11.12"))

  property("semver reconciliation rejects a major/minor mismatch") {

    a[StrictRule] should be thrownBy {
      orThrow {
        updateReport(
          argonautShapeless("6.1"),
          scalaFullVersion = "2.11.12",
          configure = _.withReconciliation(semverAll)
        )
      }
    }
  }

  property("semver reconciliation accepts a patch-level difference") {

    val res = updateReport(
      argonautShapeless("6.2"),
      scalaFullVersion = "2.11.12",
      configure = _.withReconciliation(semverAll)
    )

    val _ = orThrow(res)
  }

  property("strict reconciliation rejects what semver accepts") {

    a[StrictRule] should be thrownBy {
      orThrow {
        updateReport(
          argonautShapeless("6.2"),
          scalaFullVersion = "2.11.12",
          configure = _.withReconciliation(strictAll)
        )
      }
    }
  }

  // from shared-1/auto-scala-library
  private def scalaLibraryOf(autoScalaLibrary: Boolean): Set[String] = {
    val report = orThrow {
      updateReport(
        Seq("com.chuusai" % "shapeless_2.12" % "2.3.2" % Compile),
        scalaFullVersion = "2.12.20",
        configure = _.withAutoScalaLibrary(autoScalaLibrary)
      )
    }
    versionsOf(report, Compile, "org.scala-lang", "scala-library")
  }

  property("autoScalaLibrary = false leaves the transitive scala-library alone") {
    // 2.12.0 is what shapeless 2.3.2 depends on - nothing bumps it
    scalaLibraryOf(autoScalaLibrary = false) shouldBe Set("2.12.0")
  }

  property("autoScalaLibrary = true bumps scala-library to the build scala version") {
    // the counterpart above only means something if this one differs
    scalaLibraryOf(autoScalaLibrary = true) shouldBe Set("2.12.20")
  }

  // from shared-2/profiles
  property("maven profiles are taken into account") {

    val report = orThrow {
      updateReport(
        Seq("org.apache.spark" %% "spark-sql" % "2.4.3" % Compile),
        scalaFullVersion = "2.12.8",
        configure = _.withMavenProfiles(Vector("hadoop-3.1"))
      )
    }

    val hadoopVersions = versionsOf(report, Compile, "org.apache.hadoop", "hadoop-client")

    withClue(s"hadoop-client versions: $hadoopVersions") {
      hadoopVersions should not be empty
      hadoopVersions.forall(_.startsWith("3.1.")) shouldBe true
    }
  }

  // from shared-2/missingok
  property("missingOk also applies to a classifiers resolution") {

    def report(configure: CoursierConfiguration => CoursierConfiguration) =
      orThrow {
        updateReport(
          Seq(
            "com.chuusai" %% "shapeless" % "2.3.3" % Compile,
            // non-existing
            "org.webjars" % "npm" % "0.0.99" % Compile
          ),
          scalaFullVersion = "2.13.16",
          updateConfiguration = UpdateConfiguration().withMissingOk(true),
          configure = configure
        )
      }

    val plain = report(identity)
    val classifiers = report(_.withHasClassifiers(true).withClassifiers(Vector("sources")))

    modulesIn(plain, Compile).map(_.name) should contain("shapeless_2.13")
    modulesIn(classifiers, Compile).map(_.name) should contain("shapeless_2.13")

    val classifierArtifacts = classifiers
      .configuration(Compile)
      .toSeq
      .flatMap(_.modules)
      .flatMap(_.artifacts)
      .map(_._2.getName)

    classifierArtifacts should contain("shapeless_2.13-2.3.3-sources.jar")
  }

}
