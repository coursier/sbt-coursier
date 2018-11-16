{
  sys.props.get("plugin.version") match {
    case None =>
      Seq()
    case Some(pluginVersion) =>
      Seq(addSbtPlugin("io.get-coursier" % "sbt-coursier" % pluginVersion))
  }
}

addSbtPlugin("com.lucidchart" % "sbt-scalafmt" % "1.15")
