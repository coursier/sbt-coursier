package coursier
package test

import utest._
import scala.async.Async.{ async, await }

import coursier.Platform.fetch
import coursier.test.compatibility._

object CentralTests extends TestSuite {

  val repositories = Seq[Repository](
    MavenRepository("https://repo1.maven.org/maven2/")
  )

  def resolve(
    deps: Set[Dependency],
    filter: Option[Dependency => Boolean] = None,
    extraRepo: Option[Repository] = None
  ) = {
    val repositories0 = extraRepo.toSeq ++ repositories

    Resolution(deps, filter = filter)
      .process
      .run(repositories0)
      .runF
  }

  def repr(dep: Dependency) =
    (
      Seq(
        dep.module.organization,
        dep.module.name,
        dep.attributes.`type`
      ) ++
      Some(dep.attributes.classifier)
        .filter(_.nonEmpty)
        .toSeq ++
      Seq(
        dep.version
      )
    ).mkString(":")

  def resolutionCheck(
    module: Module,
    version: String,
    extraRepo: Option[Repository] = None,
    configuration: String = ""
  ) =
    async {
      val expected =
        await(
          textResource(s"resolutions/${module.organization}/${module.name}/$version")
        )
        .split('\n')
        .toSeq

      val dep = Dependency(module, version, configuration = configuration)
      val res = await(resolve(Set(dep), extraRepo = extraRepo))

      val result = res
        .dependencies
        .toVector
        .map(repr)
        .sorted
        .distinct

      for (((e, r), idx) <- expected.zip(result).zipWithIndex if e != r)
        println(s"Line $idx:\n  expected: $e\n  got:$r")

      assert(result == expected)
    }

  val tests = TestSuite {
    'logback{
      async {
        val dep = Dependency(Module("ch.qos.logback", "logback-classic"), "1.1.3")
        val res = await(resolve(Set(dep)))
          .copy(projectCache = Map.empty, errorCache = Map.empty) // No validating these here

        val expected = Resolution(
          rootDependencies = Set(dep),
          dependencies = Set(
            dep.withCompileScope,
            Dependency(Module("ch.qos.logback", "logback-core"), "1.1.3").withCompileScope,
            Dependency(Module("org.slf4j", "slf4j-api"), "1.7.7").withCompileScope))

        assert(res == expected)
      }
    }
    'asm{
      async {
        val dep = Dependency(Module("org.ow2.asm", "asm-commons"), "5.0.2")
        val res = await(resolve(Set(dep)))
          .copy(projectCache = Map.empty, errorCache = Map.empty) // No validating these here

        val expected = Resolution(
          rootDependencies = Set(dep),
          dependencies = Set(
            dep.withCompileScope,
            Dependency(Module("org.ow2.asm", "asm-tree"), "5.0.2").withCompileScope,
            Dependency(Module("org.ow2.asm", "asm"), "5.0.2").withCompileScope))

        assert(res == expected)
      }
    }
    'jodaVersionInterval{
      async {
        val dep = Dependency(Module("joda-time", "joda-time"), "[2.2,2.8]")
        val res0 = await(resolve(Set(dep)))
        val res = res0.copy(projectCache = Map.empty, errorCache = Map.empty)

        val expected = Resolution(
          rootDependencies = Set(dep),
          dependencies = Set(
            dep.withCompileScope))

        assert(res == expected)
        assert(res0.projectCache.contains(dep.moduleVersion))

        val proj = res0.projectCache(dep.moduleVersion)._2
        assert(proj.version == "2.8")
      }
    }
    'spark{
      resolutionCheck(
        Module("org.apache.spark", "spark-core_2.11"),
        "1.3.1"
      )
    }
    'argonautShapeless{
      resolutionCheck(
        Module("com.github.alexarchambault", "argonaut-shapeless_6.1_2.11"),
        "0.2.0"
      )
    }
    'snapshotMetadata{
      // Let's hope this one won't change too much
      resolutionCheck(
        Module("com.github.fommil", "java-logging"),
        "1.2-SNAPSHOT",
        configuration = "runtime",
        extraRepo = Some(MavenRepository("https://oss.sonatype.org/content/repositories/public/"))
      )
    }
  }

}