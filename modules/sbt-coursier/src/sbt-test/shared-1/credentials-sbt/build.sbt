scalaVersion := "2.11.8"

resolvers += "authenticated" at sys.env("TEST_REPOSITORY")

credentials += Credentials(
  "",
  sys.env("TEST_REPOSITORY_HOST"),
  sys.env("TEST_REPOSITORY_USER"),
  sys.env("TEST_REPOSITORY_PASSWORD")
)

libraryDependencies += "com.abc" % "test" % "0.1"

resolvers += Resolver.file("space-repo", file(raw"/tmp/space the final frontier/repo"))(Resolver.ivyStylePatterns)
