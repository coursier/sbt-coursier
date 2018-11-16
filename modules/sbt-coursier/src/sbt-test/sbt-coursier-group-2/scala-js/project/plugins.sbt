{
  sys.props.get("plugin.version") match {
    case None =>
      Seq()
    case Some(pluginVersion) =>
      Seq(addSbtPlugin("io.get-coursier" % "sbt-coursier" % pluginVersion))
  }
}

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.19")
