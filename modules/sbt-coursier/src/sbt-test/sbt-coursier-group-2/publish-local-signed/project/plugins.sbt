{
  sys.props.get("plugin.version") match {
    case None =>
      Seq()
    case Some(pluginVersion) =>
      Seq(addSbtPlugin("io.get-coursier" % "sbt-coursier" % pluginVersion))
  }
}

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.2-1")
