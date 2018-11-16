{
  sys.props.get("plugin.version") match {
    case None =>
      Seq()
    case Some(pluginVersion) =>
      Seq(addSbtPlugin("io.get-coursier" % "sbt-coursier" % pluginVersion))
  }
}

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.2")
