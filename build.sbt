
import Settings._

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
  )
))

val coursierVersion = "1.1.0-M8"

lazy val `sbt-shared` = project
  .in(file("modules/sbt-shared"))
  .settings(
    shared,
    libraryDependencies ++= Seq(
      "io.get-coursier" %% "coursier" % coursierVersion,
      "io.get-coursier" %% "coursier-cache" % coursierVersion,
      "io.get-coursier" %% "coursier-extra" % coursierVersion,
      "org.scala-sbt" %% "librarymanagement-ivy" % "1.0.2"
    )
  )

lazy val `sbt-coursier` = project
  .in(file("modules/sbt-coursier"))
  .enablePlugins(ScriptedPlugin)
  .dependsOn(`sbt-shared`)
  .settings(
    plugin,
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.6.4" % Test,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    libraryDependencies +="io.get-coursier" %% "coursier-scalaz-interop" % coursierVersion,
    scriptedDependencies := {
      scriptedDependencies.value

      // TODO Get dependency projects automatically
      // (but shouldn't scripted itself handle that…?)
      publishLocal.in(`sbt-shared`).value
    }
  )

lazy val `lm-coursier` = project
  .in(file("modules/lm-coursier"))
  .enablePlugins(ContrabandPlugin, JsonCodecPlugin, SbtScriptedIT)
  .dependsOn(`sbt-shared`)
  .settings(
    shared,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % Test,
    managedSourceDirectories in Compile +=
      baseDirectory.value / "src" / "main" / "contraband-scala",
    sourceManaged in (Compile, generateContrabands) := baseDirectory.value / "src" / "main" / "contraband-scala",
    contrabandFormatsForType in generateContrabands in Compile := DatatypeConfig.getFormats,
    scalacOptions in (Compile, console) --=
      Vector("-Ywarn-unused-import", "-Ywarn-unused", "-Xlint"),
    sbtTestDirectory := sbtTestDirectory.in(`sbt-coursier`).value
  )

lazy val `sbt-pgp-coursier` = project
  .in(file("modules/sbt-pgp-coursier"))
  .enablePlugins(ScriptedPlugin)
  .dependsOn(`sbt-coursier`)
  .settings(
    plugin,
    libraryDependencies += {
      val sbtv = CrossVersion.binarySbtVersion(sbtVersion.in(pluginCrossBuild).value)
      val sv = scalaBinaryVersion.value
      val ver = "1.1.1"
      Defaults.sbtPluginExtra("com.jsuereth" % "sbt-pgp" % ver, sbtv, sv)
    },
    scriptedDependencies := {
      scriptedDependencies.value
      // TODO Get dependency projects automatically
      scriptedDependencies.in(`sbt-coursier`).value
    }
  )

lazy val `sbt-shading` = project
  .in(file("modules/sbt-shading"))
  .enablePlugins(ScriptedPlugin, ShadingPlugin)
  .dependsOn(`sbt-coursier`)
  .settings(
    plugin,
    shading,
    libraryDependencies += "io.get-coursier.jarjar" % "jarjar-core" % "1.0.1-coursier-1" % "shaded",
    // dependencies of jarjar-core - directly depending on these so that they don't get shaded
    libraryDependencies ++= Seq(
      "com.google.code.findbugs" % "jsr305" % "2.0.2",
      "org.ow2.asm" % "asm-commons" % "5.2",
      "org.ow2.asm" % "asm-util" % "5.2",
      "org.slf4j" % "slf4j-api" % "1.7.25"
    ),
    scriptedDependencies := {
      scriptedDependencies.value
      // TODO Get dependency projects automatically
      scriptedDependencies.in(`sbt-coursier`).value
    }
  )

lazy val `sbt-coursier-root` = project
  .in(file("."))
  .aggregate(
    `sbt-shared`,
    `sbt-coursier`,
    `sbt-pgp-coursier`,
    `sbt-shading`,
    `lm-coursier`
  )
  .settings(
    shared,
    skip.in(publish) := true
  )

