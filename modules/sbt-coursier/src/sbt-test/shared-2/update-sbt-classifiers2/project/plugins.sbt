addSbtPlugin {

  val name = sys.props.getOrElse(
    "plugin.name",
    sys.error("plugin.name Java property not set")
  )
  val version = sys.props.getOrElse(
    "plugin.version",
    sys.error("plugin.version Java property not set")
  )

  "io.get-coursier" % name % version
}

//libraryDependencies += "org.webjars" % "npm" % "4.2.0"
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.0")