import Settings._

def dataclassScalafixV = "0.3.0"

inThisBuild(List(
  organization := "io.get-coursier",
  homepage := Some(url("https://github.com/coursier/sbt-coursier")),
  // The version is deduced from the "v*" git tags, see project/Version.scala. This replaces
  // the scheme of sbt-dynver (which sbt-ci-release relies on), whose snapshot versions carry
  // a distance and a commit hash.
  version := Version.compute(dynverGitDescribeOutput.value),
  // isSnapshot drives where sbt-ci-release publishes (the Central snapshot repository, or the
  // local staging directory that gets bundled and uploaded), so keep it in sync with the
  // version above rather than with sbt-dynver's own notion of a snapshot.
  isSnapshot := version.value.endsWith("-SNAPSHOT"),
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
  // jline, pulled by librarymanagement-ivy, calls jansi 1.x APIs from its Windows
  // terminal. Since coursier 2.1.25-M26, coursier-cache pulls jansi 2.x (via
  // windows-ansi 0.0.6), which doesn't have those any more, and wins over the
  // jansi classes jline bundles. Keep jline away from the terminal in tests, else
  // they crash on Windows with
  // NoSuchMethodError: org.fusesource.jansi.AnsiConsole.wrapOutputStream
  Test / javaOptions += "-Djline.terminal=none",
  semanticdbEnabled := true,
  semanticdbVersion := "4.13.10",
  scalafixDependencies += "net.hamnaberg" %% "dataclass-scalafix" % dataclassScalafixV,
  libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always",
  // the coursier modules we depend on are built from sources and published there
  // by scripts/publish-local-coursier.sh
  resolvers += MavenCache("coursier-snapshot", (ThisBuild / baseDirectory).value / "m2-repo")
))

// coursier version published locally by scripts/publish-local-coursier.sh (from the
// coursier sources, at the tag pinned there)
def coursierVersion0 = "SNAPSHOT"
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

// lmcoursier.internal.jniutils.LmNativeApi, in the lm-coursier sources, implements the
// coursier.jniutils.NativeApi of io.get-coursier.jniutils:windows-jni-utils (pulled by coursier via
// coursier-paths) for lm-coursier-shaded, where the latter ends up shaded. JNI ties native
// functions to class names, so that the native methods of the shaded DefaultNativeApi can't be
// bound, whereas the ones of LmNativeApi (whose package isn't shaded) are implemented by the DLL of
// windows-jni-utils. lmcoursier.internal.JniUtilsSetup passes LmNativeApi to NativeApi.set.
//
// LmNativeApi.java (and its GraalVM native image configuration) are copied from
// windows-jni-utils-lmcoursier/ in the coursier/jni-utils repository, at the tag of the version
// below, as jni-utils doesn't publish that module any more since 0.5.0 (only the first line of
// LmNativeApi.java differs, saying where it comes from). That version needs to be
// the windows-jni-utils version pulled by coursier, so that the native functions LmNativeApi
// calls are the ones of the DLL coursier comes with.
// scripts/check-jni-utils-version.sh checks both of these, and is run on CI.
val jniUtilsVersion = "0.5.0"

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
    Compile / unmanagedResourceDirectories ++= (`lm-coursier` / Compile / unmanagedResourceDirectories).value,
    // Publishing a mostly empty scaladoc JAR, not to push too much data
    // upon each release, to stay under the Maven Central publishing limits.
    // Empty mappings rather than only empty doc sources, so that a stale api
    // directory doesn't get packaged.
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / mappings := Seq.empty,
    shadedModules += "io.get-coursier" %% "coursier",
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
        "io.github.alexarchambault.nativeterm",
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
      "net.hamnaberg" %% "dataclass-annotation" % dataclassScalafixV % Provided,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.14.0",
      "org.scala-lang.modules" %% "scala-xml" % "2.5.0", // depending on that one so that it doesn't get shaded
      "org.slf4j" % "slf4j-api" % "1.7.36", // depending on that one so that it doesn't get shaded either
      // pulled by coursier-cache via native-terminal. Depending on it so that it doesn't get shaded
      // either: JNI ties its native functions to the names of its classes (org.jline.nativ.*), and
      // it loads its native libraries from resources under org/jline/nativ/. sbt itself also
      // depends on it (via jline-terminal-jni).
      "org.jline" % "jline-native" % "4.7.0",
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

// Same as the default sonaBundle of sbt, minus the checksums of the PGP signatures.
//
// Sonatype Central wants checksums for the published artifacts, not for their ".asc"
// signatures: Sonatype's own documented bundle layout has none of the latter
// (https://central.sonatype.org/publish/publish-portal-upload/), and neither do artifacts
// published with Maven or Gradle. sbt checksums every file it publishes, signatures included,
// which uploads files that nothing ever reads.
Global / sonaBundle := {
  val staging = stagingDirectory.value
  val bundle = (ThisBuild / baseDirectory).value / "target" / "sona-bundle" / "bundle.zip"
  val content = sbt.io.Path.contentOf(staging).filterNot {
    case (_, path) => path.endsWith(".asc.md5") || path.endsWith(".asc.sha1")
  }
  IO.delete(bundle)
  // time = Some(0L), like sbt does, so that the bundle is reproducible
  IO.zip(content, bundle, Some(0L))
  bundle
}

Global / onChangedBuildSource := ReloadOnSourceChanges
