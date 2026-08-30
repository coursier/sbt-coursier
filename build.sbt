import Settings._

def dataclassScalafixV = "0.3.0"

inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(url("https://github.com/coursier/sbt-coursier")),
  licenses := Seq("Apache 2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
  developers := List(
    Developer(
      "alexarchambault",
      "Alexandre Archambault",
      "",
      url("https://github.com/alexarchambault")
    )
  ),
  Test / fork := true,
  semanticdbEnabled := true,
  semanticdbVersion := "4.13.10",
  scalafixDependencies += "net.hamnaberg" %% "dataclass-scalafix" % dataclassScalafixV,
  libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"
))

def coursierVersion0 = "2.1.23"
def coursierDep = ("io.get-coursier" %% "coursier" % coursierVersion0)
  .exclude("org.codehaus.plexus", "plexus-archiver")
  .exclude("org.codehaus.plexus", "plexus-container-default")

def dataclassGen(data: Reference) = Def.taskDyn {
  val root = (ThisBuild / baseDirectory).value.toURI.toString
  val from = (data / Compile / sourceDirectory).value
  val to = (Compile / sourceManaged).value
  val outFrom = from.toURI.toString.stripSuffix("/").stripPrefix(root)
  val outTo = to.toURI.toString.stripSuffix("/").stripPrefix(root)
  (data / Compile / compile).value
  Def.task {
    (data / Compile / scalafix)
      .toTask(s" --rules GenerateDataClass --out-from=$outFrom --out-to=$outTo")
      .value
    (to ** "*.scala").get
  }
}

lazy val preTest = taskKey[Unit]("prep steps before tests")

// Only a code generation input for lm-coursier, never published.
lazy val definitions = project
  .in(file("modules/definitions"))
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    publish / skip := true,
    libraryDependencies ++= Seq(
      coursierDep,
      "net.hamnaberg" %% "dataclass-annotation" % dataclassScalafixV % Provided,
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.3.4",
    ),
  )

// FIXME Ideally, we should depend on the same version of io.get-coursier.jniutils:windows-jni-utils that
// io.get-coursier::coursier depends on.
val jniUtilsVersion = "0.3.4"

// Not published, only lm-coursier-shaded is. This project holds the sources
// (which lm-coursier-shaded picks up via Compile / sources) and the tests.
lazy val `lm-coursier` = project
  .in(file("modules/lm-coursier"))
  .disablePlugins(MimaPlugin)
  .settings(
    shared,
    publish / skip := true,
    libraryDependencies ++= Seq(
      coursierDep,
      "io.get-coursier" %% "coursier-sbt-maven-repository" % coursierVersion0,
      "io.get-coursier.jniutils" % "windows-jni-utils-lmcoursier" % jniUtilsVersion,
      "net.hamnaberg" %% "dataclass-annotation" % dataclassScalafixV % Provided,
      // We depend on librarymanagement-ivy rather than just
      // librarymanagement-core to handle the ModuleDescriptor passed
      // to DependencyResolutionInterface.update, which is an
      // IvySbt#Module (seems DependencyResolutionInterface.moduleDescriptor
      // is ignored).
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.3.4",
      "com.lihaoyi" %% "fansi" % "0.5.1" % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    ),
    Test / exportedProducts := {
      (Test / preTest).value
      (Test / exportedProducts).value
    },
    Test / preTest := {
      (customProtocolForTest / publishLocal).value
      (customProtocolJavaForTest / publishLocal).value
    },
    Compile / sourceGenerators += dataclassGen(definitions).taskValue,
  )

lazy val `lm-coursier-shaded` = project
  .in(file("modules/lm-coursier/target/shaded-module"))
  .enablePlugins(ShadingPlugin)
  .settings(
    shared,
    Mima.settings,
    Mima.lmCoursierFilters,
    Mima.lmCoursierShadedFilters,
    Compile / sources := (`lm-coursier` / Compile / sources).value,
    // Publishing a mostly empty scaladoc JAR, not to push too much data
    // upon each release, to stay under the Maven Central publishing limits.
    // Empty mappings rather than only empty doc sources, so that a stale api
    // directory doesn't get packaged.
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / mappings := Seq.empty,
    shadedModules ++= Set(
      "io.get-coursier" %% "coursier",
      "io.get-coursier" %% "coursier-sbt-maven-repository",
      "io.get-coursier.jniutils" % "windows-jni-utils-lmcoursier"
    ),
    validNamespaces += "lmcoursier",
    validEntries ++= Set(
      // FIXME Ideally, we should just strip those from the resulting JAR…
      "README", // from google-collections via plexus-archiver (see below)
      // from plexus-util via plexus-archiver (see below)
      "licenses/extreme.indiana.edu.license.TXT",
      "licenses/javolution.license.TXT",
      "licenses/thoughtworks.TXT",
      "licenses/",
    ),
    shadingRules ++= {
      val toShade = Seq(
        "coursier",
        "dependency",
        "org.fusesource",
        "macrocompat",
        "io.github.alexarchambault.isterminal",
        "io.github.alexarchambault.windowsansi",
        "concurrentrefhashmap",
        // pulled by the plexus-archiver stuff that coursier-cache
        // depends on for now… can hopefully be removed in the future
        "com.google.common",
        "org.apache.commons",
        "org.apache.tika",
        "org.apache.xbean",
        "org.codehaus",
        "org.iq80",
        "org.tukaani",
        "com.github.plokhotnyuk.jsoniter_scala",
        "scala.cli",
        "com.github.luben.zstd",
        "javax.inject" // hope shading this is fine… It's probably pulled via plexus-archiver, that sbt shouldn't use anyway…
      )
      for (ns <- toShade)
        yield ShadingRule.moveUnder(ns, "lmcoursier.internal.shaded")
    },
    libraryDependencies ++= Seq(
      coursierDep,
      "io.get-coursier" %% "coursier-sbt-maven-repository" % coursierVersion0,
      "io.get-coursier.jniutils" % "windows-jni-utils-lmcoursier" % jniUtilsVersion,
      "net.hamnaberg" %% "dataclass-annotation" % dataclassScalafixV % Provided,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.4.0", // depending on that one so that it doesn't get shaded
      "org.slf4j" % "slf4j-api" % "1.7.36", // depending on that one so that it doesn't get shaded either
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.3.4",
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    )
  )

lazy val customProtocolForTest = project
  .in(file("modules/custom-protocol-for-test"))
  .settings(
    scalaVersion := scala212,
    organization := "org.example",
    moduleName := "customprotocol-handler",
    version := "0.1.0",
    dontPublish
  )

lazy val customProtocolJavaForTest = project
  .in(file("modules/custom-protocol-java-for-test"))
  .settings(
    crossPaths := false,
    organization := "org.example",
    moduleName := "customprotocoljava-handler",
    version := "0.1.0",
    dontPublish
  )

lazy val `sbt-coursier-root` = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .aggregate(
    definitions,
    `lm-coursier`,
    `lm-coursier-shaded`
  )
  .settings(
    shared,
    (publish / skip) := true
  )

Global / onChangedBuildSource := ReloadOnSourceChanges
