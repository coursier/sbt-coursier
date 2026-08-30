package lmcoursier

import java.net.URL
import java.nio.file.{Files, Path, Paths}

import lmcoursier.credentials.Credentials
import lmcoursier.internal.SbtCoursierCache
import lmcoursier.definitions.{
  Classifier, Configuration => CConfiguration, Extension, Info, Module, ModuleName, Organization, Project,
  Publication, Type
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import sbt.librarymanagement._
import sbt.librarymanagement.syntax._

/**
 * Ported scripted tests that need something set up around them: the authenticated repository
 * `with-test-repo.sh` serves, or hand-built inter-project dependencies.
 *
 * The credentials properties are skipped unless TEST_REPOSITORY is set, so that a bare
 * `lm-coursier/test` still works; CI always runs them, since scripts/ci.sh goes through
 * metadata/scripts/with-test-repo.sh.
 */
final class FixturesPortSpec extends AnyPropSpec with Matchers with PortedTestHelpers {

  private def testRepositoryOpt = sys.env.get("TEST_REPOSITORY").filter(_.nonEmpty)
  private def repoUser = sys.env.getOrElse("TEST_REPOSITORY_USER", "user")
  private def repoPassword = sys.env.getOrElse("TEST_REPOSITORY_PASSWORD", "pass")

  private def withTestRepository(f: String => Unit): Unit =
    testRepositoryOpt match {
      case Some(repo) => f(repo)
      case None => cancel("TEST_REPOSITORY not set - run through metadata/scripts/with-test-repo.sh")
    }

  // the one module the test repository serves, declared intransitive like the scripted tests did
  private val testDep = Seq(("com.abc" % "test" % "0.1").intransitive() % Compile)

  private def resolveAgainstTestRepository(repo: String, credentials: Seq[Credentials]) =
    updateReport(
      testDep,
      scalaFullVersion = "2.12.20",
      configure = c =>
        c.withResolvers(c.resolvers :+ MavenRepository("authenticated", repo))
          .withCredentials(credentials)
    )

  // from shared-1/credentials
  property("inline credentials unlock an authenticated repository") {
    withTestRepository { repo =>
      val credentials = Credentials(new java.net.URI(repo).getHost, repoUser, repoPassword)
        .withHttpsOnly(false)
        .withMatchHost(true)

      val report = orThrow(resolveAgainstTestRepository(repo, Seq(credentials)))
      modulesIn(report, Compile).map(_.name) should contain("test")
    }
  }

  property("without credentials the same repository refuses") {
    withTestRepository { repo =>
      // the property above only means something if this one fails
      resolveAgainstTestRepository(repo, Nil).isLeft shouldBe true
    }
  }

  // from shared-1/credentials-from-file
  property("credentials read from a file unlock an authenticated repository") {
    withTestRepository { repo =>
      val host = new java.net.URI(repo).getHost
      val content =
        s"""foo.host=$host
           |foo.username=$repoUser
           |foo.password=$repoPassword
           |foo.auto=true
           |foo.https-only=false
           |""".stripMargin

      val dest = Files.createTempFile("credentials", ".properties")
      Files.write(dest, content.getBytes("UTF-8"))

      val report = orThrow(resolveAgainstTestRepository(repo, Seq(Credentials(dest.toFile))))
      modulesIn(report, Compile).map(_.name) should contain("test")
    }
  }

  // from shared-2/missing-credentials
  property("a credentials file that does not exist does not break resolution") {

    val missing = new java.io.File("nope/nope/nope/nope/nope")
    missing.exists() shouldBe false

    val report = orThrow {
      updateReport(
        Seq("com.chuusai" %% "shapeless" % "2.3.3" % Compile),
        scalaFullVersion = "2.12.20",
        configure = _.withCredentials(Seq(Credentials(missing)))
      )
    }

    modulesIn(report, Compile).map(_.name) should contain("shapeless_2.12")
  }

  // ---- inter-project ----

  // A project needs a maven-shaped configuration graph for its own dependencies to be
  // traversed - with a flat map they are silently dropped, and the project resolves alone.
  private val mavenConfigurations: Map[CConfiguration, Seq[CConfiguration]] = Map(
    CConfiguration("compile") -> Seq.empty,
    CConfiguration("runtime") -> Seq(CConfiguration("compile")),
    CConfiguration("default") -> Seq(CConfiguration("runtime")),
    CConfiguration("test") -> Seq(CConfiguration("runtime")),
    CConfiguration("provided") -> Seq.empty,
    CConfiguration("optional") -> Seq.empty
  )

  private def project(org: String, name: String, version: String, dependencies: Seq[(String, String, String)]) =
    Project(
      Module(Organization(org), ModuleName(name), Map()),
      version,
      dependencies.map {
        case (depOrg, depName, depVer) =>
          CConfiguration("compile") -> lmcoursier.definitions.Dependency(
            Module(Organization(depOrg), ModuleName(depName), Map()),
            depVer,
            CConfiguration("default"),
            Set.empty,
            Publication("", Type(""), Extension(""), Classifier("")),
            optional = false,
            transitive = true
          )
      },
      mavenConfigurations,
      Nil,
      None,
      Nil,
      Info("", "", Nil, Nil, None)
    )

  // lm-coursier only consults interProjectDependencies when the module being resolved is itself
  // one of them - sbt passes every project of the build, so the tests below pass "self" too
  private def self(dependencies: Seq[(String, String, String)]) =
    project(selfModule.organization, selfModule.name, selfModule.revision, dependencies)

  // from shared-1/inter-project
  property("an inter-project dependency shadows the module with the same coordinates") {

    // a project with the same maven coordinates as the real shapeless 2.3.3
    val shapelessMock = project("com.chuusai", "shapeless_2.12", "2.3.3", Nil)

    def report(interProject: Vector[Project]) =
      orThrow {
        updateReport(
          Seq("com.github.alexarchambault" %% "argonaut-shapeless_6.2" % "1.2.0-M11" % Compile),
          scalaFullVersion = "2.12.8",
          configure = _.withInterProjectDependencies(interProject)
        )
      }

    def shapelessArtifacts(r: UpdateReport) =
      r.configuration(Compile)
        .toSeq
        .flatMap(_.modules)
        .filter(_.module.name == "shapeless_2.12")
        .flatMap(_.artifacts)

    val shadowed =
      report(Vector(self(Seq(("com.github.alexarchambault", "argonaut-shapeless_6.2_2.12", "1.2.0-M11"))), shapelessMock))
    val real = report(Vector.empty)

    versionsOf(shadowed, Compile, "com.chuusai", "shapeless_2.12") shouldBe Set("2.3.3")

    // the mock carries no artifacts, so nothing is fetched for shapeless...
    withClue(shapelessArtifacts(shadowed).toString) {
      shapelessArtifacts(shadowed) shouldBe empty
    }
    // ...which only means something because the real one does have some
    shapelessArtifacts(real) should not be empty
  }

  // from shared-1/caller
  property("callers are recorded in the report") {

    val core = project("com.example", "core_2.12", "0.1.0", Nil)
    val util = project("com.example", "util_2.12", "0.1.0", Seq(("com.example", "core_2.12", "0.1.0")))
    val app = self(Seq(("com.example", "util_2.12", "0.1.0"), ("com.chuusai", "shapeless_2.12", "2.3.3")))

    val report = orThrow {
      updateReport(
        Seq(
          "com.example" % "util_2.12" % "0.1.0" % Compile,
          "com.chuusai" %% "shapeless" % "2.3.3" % Compile
        ),
        scalaFullVersion = "2.12.8",
        configure = _.withInterProjectDependencies(Vector(app, core, util))
      )
    }

    val modules = report.configuration(Compile).toSeq.flatMap(_.modules)

    // a module report for the subproject dependency, with its caller
    val coreReport = modules.find(_.module.name == "core_2.12").getOrElse {
      sys.error(s"report for core is missing: ${modules.map(_.module.name)}")
    }
    coreReport.callers.map(_.caller.name) should contain("util_2.12")

    // and one for the library dependency, called by the project itself
    val shapelessReport = modules.find(_.module.name == "shapeless_2.12").getOrElse {
      sys.error("report for shapeless is missing")
    }
    shapelessReport.callers.map(_.caller.name) should contain("test")
  }

  // from shared-1/from-wrong-url
  property("a fallback url that 404s falls back to the repository") {

    val wrong = FallbackDependency(
      Module(Organization("com.chuusai"), ModuleName("shapeless_2.12"), Map()),
      "2.3.2",
      // this artifact does not exist - 2.3.242 was never released
      new URL("https://repo1.maven.org/maven2/com/chuusai/shapeless_2.12/2.3.242/shapeless_2.12-2.3.242.jar"),
      changing = false
    )

    val report = orThrow {
      updateReport(
        Seq("com.chuusai" %% "shapeless" % "2.3.2" % Compile),
        scalaFullVersion = "2.12.8",
        configure = _.withFallbackDependencies(Vector(wrong))
      )
    }

    // the real jar is still what ends up on the classpath
    val jars = report
      .configuration(Compile)
      .toSeq
      .flatMap(_.modules)
      .flatMap(_.artifacts)
      .map(_._2.getName)

    jars should contain("shapeless_2.12-2.3.2.jar")
  }

  // from shared-1/default-artifact
  // adapted, as the scripted test was, from sbt's own dependency-management/default-artifact
  property("a module whose default artifact does not exist still resolves") {

    // an ivy repository publishing b1.jar and fake.jar, but no b.jar
    val repo = Paths.get(getClass.getResource("/default-artifact-repo").toURI).toFile
    val resolver = Resolver.file("buggy", repo)(
      Patterns(
        ivyPatterns = Vector("[organization]/[module]/[revision]/ivy.xml"),
        artifactPatterns = Vector("[organization]/[module]/[revision]/[artifact].[ext]"),
        isMavenCompatible = false,
        descriptorOptional = true,
        skipConsistencyCheck = true
      )
    )

    val report = orThrow {
      updateReport(
        Seq(
          ("a" % "b" % "1.0.0" % "compile->runtime").artifacts(Artifact("b1", "jar", "jar")),
          ("a" % "b" % "1.0.0" % "test->runtime").artifacts(Artifact("b1", "jar", "jar"))
        ),
        scalaFullVersion = "2.12.20",
        configure = c => c.withResolvers(c.resolvers :+ resolver)
      )
    }

    val names = report.configuration(Compile).toSeq.flatMap(_.modules).flatMap(_.artifacts).map(_._1.name)
    names should contain("b1")
  }

  // from shared-2/tests-classifier
  property("a tests classifier resolves alongside the main artifact") {

    val report = orThrow {
      updateReport(
        Seq(
          "org.apache.hadoop" % "hadoop-common" % "2.7.1" % Compile,
          ("org.apache.hadoop" % "hadoop-common" % "2.7.1" % Compile).classifier("tests")
        ),
        scalaFullVersion = "2.12.20"
      )
    }

    val classifiers = report
      .configuration(Compile)
      .toSeq
      .flatMap(_.modules)
      .filter(_.module.name == "hadoop-common")
      .flatMap(_.artifacts)
      .map(_._1.classifier)
      .toSet

    classifiers should contain(None)
    classifiers should contain(Some("tests"))
  }

  // from shared-1/cache-local
  property("the in-memory cache has to be cleared for a removed artifact to be seen") {

    val dir = Files.createTempDirectory("lm-coursier-local-repo")
    writeLocalArtifact(dir)

    def resolve() =
      updateReport(
        Seq(("org.example" % "def" % "1.0").intransitive() % Compile),
        scalaFullVersion = "2.12.20",
        configure = _.withResolvers(Vector(MavenRepository("filesys", dir.toUri.toString)))
      )

    // published and resolvable
    orThrow(resolve())

    deleteRecursively(dir)

    // still resolvable, because SbtCoursierCache still holds the resolution -
    // this is what the scripted test's "> clean" step was there for
    orThrow(resolve())

    SbtCoursierCache.default.clear()

    // and gone once the cache is cleared
    resolve().isLeft shouldBe true
  }

  private def writeLocalArtifact(dir: Path): Unit = {
    val d = dir.resolve("org/example/def/1.0")
    Files.createDirectories(d)
    Files.write(
      d.resolve("def-1.0.pom"),
      ("<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>" +
        "<artifactId>def</artifactId><version>1.0</version></project>").getBytes("UTF-8")
    )
    val out = new java.util.zip.ZipOutputStream(Files.newOutputStream(d.resolve("def-1.0.jar")))
    try {
      out.putNextEntry(new java.util.zip.ZipEntry("a.txt"))
      out.write("x".getBytes("UTF-8"))
      out.closeEntry()
    } finally out.close()
  }

  private def deleteRecursively(p: Path): Unit = {
    if (Files.isDirectory(p)) {
      val children = Files.list(p)
      try children.forEach(c => deleteRecursively(c))
      finally children.close()
    }
    Files.deleteIfExists(p)
    ()
  }

  // from shared-1/fallback-dependencies-inter-project
  //
  // Kept commented out on purpose: the scripted test this comes from carries a "disabled"
  // marker file, so it has not been running. The port below does pass - it is here so the
  // behaviour is written down, ready to enable if that test is ever revived.
  //
  // property("a fallback dependency is reached through an inter-project dependency") {
  //
  //   val shapeless = FallbackDependency(
  //     Module(Organization("com.chuusai"), ModuleName("shapeless_2.12"), Map()),
  //     "2.3.234",
  //     new URL("https://repo1.maven.org/maven2/com/chuusai/shapeless_2.12/2.3.3/shapeless_2.12-2.3.3.jar"),
  //     changing = false
  //   )
  //
  //   val a = project("com.example", "a_2.12", "0.1.0", Seq(("com.chuusai", "shapeless_2.12", "2.3.234")))
  //   val b = self(Seq(("com.example", "a_2.12", "0.1.0")))
  //
  //   val report = orThrow {
  //     updateReport(
  //       Seq("com.example" % "a_2.12" % "0.1.0" % Compile),
  //       scalaFullVersion = "2.12.8",
  //       configure = _.withInterProjectDependencies(Vector(b, a)).withFallbackDependencies(Vector(shapeless))
  //     )
  //   }
  //
  //   versionsOf(report, Compile, "com.chuusai", "shapeless_2.12") shouldBe Set("2.3.234")
  // }

}
