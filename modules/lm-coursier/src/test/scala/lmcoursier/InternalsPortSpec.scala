package lmcoursier

import java.nio.file.Files

import coursier.maven.MavenRepositoryLike
import lmcoursier.definitions.{Configuration, Info, Module, ModuleName, Organization, Project}
import lmcoursier.internal.{ResolutionParams, Resolvers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import sbt.internal.util.ConsoleLogger
import sbt.librarymanagement.{MavenRepository, Resolver}

/**
 * Ported scripted tests that need no resolution at all - they exercise lm-coursier internals
 * directly, so unlike the rest of the port they cost nothing and touch no network.
 */
final class InternalsPortSpec extends AnyPropSpec with Matchers {

  private lazy val log = ConsoleLogger()

  private def repositoryOf(resolver: Resolver) =
    Resolvers.repository(
      resolver = resolver,
      ivyProperties = ResolutionParams.defaultIvyProperties(None),
      log = log,
      authentication = None,
      classLoaders = Seq(getClass.getClassLoader)
    )

  // from sbt-coursier/s3
  // the scripted test went through fm-sbt-s3-resolver's `atS3`, which builds a MavenRepository;
  // its s3 URL handler is stood in for by coursier.cache.protocol.S3Handler in the test sources
  property("s3:// resolvers are parsed as maven repositories") {

    val resolvers = Seq(
      MavenRepository("Private S3 Snapshots", "s3://s3-us-west-2.amazonaws.com/bucket-name/snapshots"),
      MavenRepository("Private S3 Releases", "s3://s3-us-west-2.amazonaws.com/bucket-name/releases")
    )

    val parsed = resolvers.flatMap(repositoryOf)

    parsed.length shouldBe resolvers.length

    def containsRepo(repo: String): Boolean = {
      val accepted = Set(repo, repo.stripSuffix("/"))
      parsed.exists {
        case m: MavenRepositoryLike => accepted(m.root)
        case _ => false
      }
    }

    withClue(parsed.toString) {
      containsRepo("s3://s3-us-west-2.amazonaws.com/bucket-name/snapshots/") shouldBe true
      containsRepo("s3://s3-us-west-2.amazonaws.com/bucket-name/releases/") shouldBe true
    }
  }

  // from shared-2/whitespace-resolver
  property("a file resolver whose path contains spaces is parsed") {

    val dir = Files.createTempDirectory("space the final frontier").resolve("repo")
    Files.createDirectories(dir)

    val resolver = Resolver.file("space-repo", dir.toFile)(Resolver.ivyStylePatterns)

    val repo = repositoryOf(resolver).getOrElse {
      sys.error(s"no repository parsed out of $resolver")
    }

    // the space has to survive as an escape, not as a raw space in the URL
    repo.toString should not include " the final frontier"
  }

  // from shared-1/dependency-overrides
  // the scripted test read this back through the plugin's coursierWriteIvyXml task
  property("dependency overrides end up in the generated ivy.xml") {

    val project = Project(
      Module(Organization("io.get-coursier.test"), ModuleName("dependency-overrides"), Map()),
      "0.1.0-SNAPSHOT",
      Nil,
      Map(Configuration("compile") -> Seq.empty),
      Nil,
      None,
      Nil,
      Info("", "", Nil, Nil, None)
    )

    val overrides = Seq(("io.get-coursier", "coursier-core_2.12", "1.1.0-M14-7"))

    val withOverrides = IvyXml(project, Nil, overrides)
    val withoutOverrides = IvyXml(project, Nil, Nil)

    withClue(withOverrides) {
      withOverrides should include("<override ")
      withOverrides should include("coursier-core_2.12")
    }

    // the counterpart above only means something if this one differs
    withoutOverrides should not include "<override "
  }

}
