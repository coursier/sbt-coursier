scalaVersion := "2.11.8"

resolvers += "authenticated" at sys.env("TEST_REPOSITORY")

coursierCredentials += "authenticated" -> coursier.Credentials(
  sys.env("TEST_REPOSITORY_USER"),
  sys.env("TEST_REPOSITORY_PASSWORD")
)

libraryDependencies += "com.abc" % "test" % "0.1"


{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}