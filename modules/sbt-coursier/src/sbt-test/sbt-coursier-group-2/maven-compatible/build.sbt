scalaVersion := "2.11.8"

resolvers += Resolver.url(
  "webjars-bintray",
  new URL("https://dl.bintray.com/scalaz/releases/")
)(
  // patterns should be ignored - and the repo be considered a maven one - because
  // isMavenCompatible is true
  Patterns(
    Resolver.ivyStylePatterns.ivyPatterns,
    Resolver.ivyStylePatterns.artifactPatterns,
    isMavenCompatible = true,
    descriptorOptional = false,
    skipConsistencyCheck = false
  )
)

libraryDependencies += "org.scalaz.stream" %% "scalaz-stream" % "0.7.1"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}