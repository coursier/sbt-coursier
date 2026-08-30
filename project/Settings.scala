import java.util.Locale

import sbt._
import sbt.Keys._

import com.jsuereth.sbtpgp._

object Settings {

  def scala212 = "2.12.20"

  lazy val shared = Seq(
    scalaVersion := scala212,
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-language:higherKinds",
      "-language:implicitConversions"
    ),
    libraryDependencies += compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  ) ++ {
    val prop = sys.props.getOrElse("publish.javadoc", "").toLowerCase(Locale.ROOT)
    if (prop == "0" || prop == "false")
      Seq(
        Compile / doc / sources := Seq.empty,
        Compile / packageDoc / publishArtifact := false
      )
    else
      Nil
  }

  lazy val dontPublish = Seq(
    publish := {},
    // we need publishing for tests
    // publish / skip := true,
  )

}
