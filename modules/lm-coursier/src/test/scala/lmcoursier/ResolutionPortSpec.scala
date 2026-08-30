package lmcoursier

import java.net.URL
import java.nio.file.Files

import scala.collection.JavaConverters._

import lmcoursier.definitions.{CacheLogger, Module, ModuleName, Organization}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import sbt.librarymanagement._
import sbt.librarymanagement.syntax._

/** Resolution-level scripted tests, ported. See [[PortedTestHelpers]]. */
final class ResolutionPortSpec extends AnyPropSpec with Matchers with PortedTestHelpers {

  private def namesIn(report: UpdateReport, config: Configuration): Seq[String] =
    modulesIn(report, config).map(_.name)

  private def artifactsIn(report: UpdateReport, config: Configuration) =
    report.configuration(config).toSeq.flatMap(_.modules).flatMap(_.artifacts).map(_._1)

  // from shared-1/exclude-dependencies
  property("excluded dependencies are dropped from the report") {

    val dep = "com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M11" % Compile

    val kept = orThrow(updateReport(Seq(dep), scalaFullVersion = "2.12.8"))
    val excluded = orThrow {
      updateReport(
        Seq(dep),
        scalaFullVersion = "2.12.8",
        configure = _.withExcludeDependencies(
          Vector(("com.chuusai", "shapeless_2.12"), ("io.argonaut", "argonaut_2.12"))
        )
      )
    }

    // the exclusions only mean something if these are there to begin with
    namesIn(kept, Compile) should contain allOf ("shapeless_2.12", "argonaut_2.12")

    namesIn(excluded, Compile) should contain("argonaut-shapeless_6.2_2.12")
    namesIn(excluded, Compile) should not contain "shapeless_2.12"
    namesIn(excluded, Compile) should not contain "argonaut_2.12"
  }

  // from shared-1/classifiers
  property("a classifier on a dependency is honoured") {

    val report = orThrow {
      updateReport(
        Seq("org.jclouds.api" % "nova" % "1.5.9" % Compile classifier "tests"),
        scalaFullVersion = "2.12.8"
      )
    }

    val classifiers = artifactsIn(report, Compile).filter(_.name == "nova").flatMap(_.classifier)
    classifiers should contain("tests")
  }

  // from shared-2/scala-sources-javadoc-jars
  property("sources and javadoc classifiers are resolved") {

    val report = orThrow {
      updateReport(
        Seq("com.chuusai" %% "shapeless" % "2.3.3" % Compile),
        scalaFullVersion = "2.12.20",
        configure = _.withHasClassifiers(true).withClassifiers(Vector("sources", "javadoc"))
      )
    }

    val shapelessArtifacts = artifactsIn(report, Compile).filter(_.name.startsWith("shapeless"))
    val classifiers = shapelessArtifacts.flatMap(_.classifier).toSet

    withClue(shapelessArtifacts.toString) {
      classifiers should contain("sources")
      classifiers should contain("javadoc")
    }
  }

  // from shared-2/per-config-resolution
  property("configurations are resolved independently") {

    val report = orThrow {
      updateReport(
        Seq(
          "io.get-coursier" %% "coursier-core" % "2.0.0-RC6" % Compile,
          // depends on coursier-core 2.0.0-RC6-16
          "io.get-coursier" %% "coursier" % "2.0.0-RC6-16" % Test
        ),
        scalaFullVersion = "2.13.16"
      )
    }

    versionsOf(report, Compile, "io.get-coursier", "coursier-core") shouldBe Set("2.0.0-RC6")
    versionsOf(report, Test, "io.get-coursier", "coursier-core") shouldBe Set("2.0.0-RC6-16")
  }

  // from shared-2/no-pom-artifact
  property("no bare POM artifacts land in the report") {

    val report = orThrow {
      updateReport(
        Seq("com.chuusai" %% "shapeless" % "2.3.3" % Compile),
        scalaFullVersion = "2.12.20"
      )
    }

    val poms = artifactsIn(report, Compile).filter(a => a.`type` == "pom" && a.classifier.isEmpty)

    withClue(poms.toString) {
      poms shouldBe empty
    }
  }

  // from shared-1/config-deps-resolution
  property("a plain multi-dependency compile resolution works") {

    val report = orThrow {
      updateReport(
        Seq(
          "org.slf4j" % "slf4j-api" % "1.7.2" % Compile,
          "ch.qos.logback" % "logback-classic" % "1.1.1" % Compile
        ),
        scalaFullVersion = "2.12.20"
      )
    }

    namesIn(report, Compile) should contain allOf ("slf4j-api", "logback-classic", "logback-core")
  }

  // from shared-1/hadoop-yarn-server-resourcemanager
  property("hadoop-yarn-server-resourcemanager resolves") {

    val report = orThrow {
      updateReport(
        Seq("org.apache.hadoop" % "hadoop-yarn-server-resourcemanager" % "2.7.1" % Compile),
        scalaFullVersion = "2.12.8"
      )
    }

    namesIn(report, Compile) should contain("hadoop-yarn-server-resourcemanager")
    artifactsIn(report, Compile).size should be > 10
  }

  // from shared-2/zookeeper
  property("zookeeper resolves") {

    val report = orThrow {
      updateReport(
        Seq("org.apache.zookeeper" % "zookeeper" % "3.5.0-alpha" % Compile),
        scalaFullVersion = "2.12.8"
      )
    }

    namesIn(report, Compile) should contain("zookeeper")
  }

  // from shared-2/maven-compatible
  property("a url resolver with isMavenCompatible is treated as a maven repository") {

    // patterns should be ignored - and the repo considered a maven one - because
    // isMavenCompatible is true
    val jitpack = Resolver.url(
      "jitpack",
      new URL("https://jitpack.io")
    )(
      Patterns(
        Resolver.ivyStylePatterns.ivyPatterns,
        Resolver.ivyStylePatterns.artifactPatterns,
        isMavenCompatible = true,
        descriptorOptional = false,
        skipConsistencyCheck = false
      )
    )

    val report = orThrow {
      updateReport(
        Seq("com.github.jupyter" % "jvm-repr" % "0.3.0" % Compile),
        scalaFullVersion = "2.12.8",
        configure = c => c.withResolvers(c.resolvers :+ jitpack)
      )
    }

    namesIn(report, Compile) should contain("jvm-repr")
  }

  // from shared-2/url - the dependency is declared in Test only
  property("a dependency fetched from a url stays in its own configuration") {

    val jsoup = FallbackDependency(
      Module(Organization("org.jsoup"), ModuleName("jsoup"), Map()),
      "1.9.1",
      new URL("http://jsoup.org/packages/jsoup-1.9.1.jar"),
      changing = false
    )

    val report = orThrow {
      updateReport(
        Seq("org.jsoup" % "jsoup" % "1.9.1" % Test),
        scalaFullVersion = "2.12.20",
        configure = _.withFallbackDependencies(Vector(jsoup))
      )
    }

    namesIn(report, Test) should contain("jsoup")
    namesIn(report, Compile) should not contain "jsoup"
  }

  // from shared-1/from
  property("a fallback url supplies the artifact") {

    val shapeless = FallbackDependency(
      Module(Organization("com.chuusai"), ModuleName("shapeless_2.12"), Map()),
      // a version that exists nowhere - only the fallback url can satisfy it
      "2.3.41",
      new URL("https://repo1.maven.org/maven2/com/chuusai/shapeless_2.12/2.3.3/shapeless_2.12-2.3.3.jar"),
      changing = false
    )

    val report = orThrow {
      updateReport(
        Seq("com.chuusai" %% "shapeless" % "2.3.41" % Compile),
        scalaFullVersion = "2.12.8",
        configure = _.withFallbackDependencies(Vector(shapeless))
      )
    }

    versionsOf(report, Compile, "com.chuusai", "shapeless") shouldBe Set("2.3.41")
  }

  // from shared-1/from-no-head - the url is a github release, which does not answer HEAD
  property("a fallback url that does not support HEAD still works") {

    val netlogo = FallbackDependency(
      Module(Organization("ccl.northwestern.edu"), ModuleName("netlogo"), Map()),
      "5.3.1",
      new URL("https://github.com/NetLogo/NetLogo/releases/download/5.3.1/NetLogo.jar"),
      changing = false
    )

    val report = orThrow {
      updateReport(
        Seq("ccl.northwestern.edu" % "netlogo" % "5.3.1" % Provided),
        scalaFullVersion = "2.12.8",
        configure = _.withFallbackDependencies(Vector(netlogo))
      )
    }

    namesIn(report, Provided) should contain("netlogo")
  }

  // from sbt-lm-coursier/cp-order - https://github.com/coursier/coursier/issues/1466
  property("classpathOrder changes the order modules come back in") {

    def order(classpathOrder: Boolean) = {
      val report = orThrow {
        updateReport(
          Seq(
            "com.typesafe.play" %% "play-test" % "2.8.0-RC1" % Test,
            "org.scalatest" %% "scalatest" % "3.0.8" % Test
          ),
          scalaFullVersion = "2.13.1",
          configure = _.withClasspathOrder(classpathOrder)
        )
      }
      modulesIn(report, Test).map(_.name)
    }

    val ordered = order(classpathOrder = true)
    val unordered = order(classpathOrder = false)

    ordered should contain("play-test_2.13")
    // same set of modules either way, but not in the same order
    ordered.toSet shouldBe unordered.toSet
    ordered should not be unordered
  }

  // from shared-1/logger
  property("a CacheLogger is told what is downloaded and what is found locally") {

    val cache = Files.createTempDirectory("lm-coursier-logger-test").toFile

    class Recording extends CacheLogger {
      val downloaded = new java.util.concurrent.ConcurrentLinkedQueue[String]
      val found = new java.util.concurrent.ConcurrentLinkedQueue[String]
      override def downloadedArtifact(url: String, success: Boolean): Unit = {
        val _ = downloaded.add(url)
      }
      override def foundLocally(url: String): Unit = {
        val _ = found.add(url)
      }
    }

    def resolveWith(logger: Recording) =
      orThrow {
        updateReport(
          // arbitrary dependency with no transitive dependencies
          Seq("org.slf4j" % "slf4j-api" % "1.7.25" % Compile),
          scalaFullVersion = "2.12.20",
          configure = _.withLogger(Some(logger)).withCache(Some(cache))
        )
      }

    // an empty cache has to fetch...
    val first = new Recording
    resolveWith(first)
    first.downloaded.asScala.exists(_.contains("slf4j-api-1.7.25.jar")) shouldBe true

    // ...and a warm one does not
    val second = new Recording
    resolveWith(second)
    second.downloaded.asScala.exists(_.contains("slf4j-api-1.7.25.jar")) shouldBe false
  }

}
