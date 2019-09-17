
plugins_(
  "com.geirsson"       % "sbt-ci-release"           % "1.3.2",
  "io.get-coursier"    % "sbt-coursier"             % sbtCoursierVersion,
  "com.typesafe"       % "sbt-mima-plugin"          % "0.6.0",
  "io.get-coursier"    % "sbt-shading"              % sbtCoursierVersion,
  "org.scala-sbt"      % "sbt-contraband"           % "0.4.4"
)

libs ++= Seq(
  "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
)


def plugins_(modules: ModuleID*) = modules.map(addSbtPlugin)
def libs = libraryDependencies
