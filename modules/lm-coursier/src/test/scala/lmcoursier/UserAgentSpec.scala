package lmcoursier

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import lmcoursier.syntax._
import org.scalatest.propspec.AnyPropSpec
import sbt.librarymanagement._
import sbt.util.Logger

import scala.collection.JavaConverters._

class UserAgentSpec extends AnyPropSpec {

  private val logger: Logger = new Logger {
    def log(level: sbt.util.Level.Value, message: => String): Unit = ()
    def success(message: => String): Unit = ()
    def trace(t: => Throwable): Unit = ()
  }

  private def requestUserAgents(conf: CoursierConfiguration): Seq[String] = {
    val agents = new ConcurrentLinkedQueue[String]
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        agents.add(Option(exchange.getRequestHeaders.getFirst("User-Agent")).getOrElse(""))
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
      }
    )
    server.start()
    try {
      val repo = MavenRepository("local-http", s"http://localhost:${server.getAddress.getPort}/maven")
      val depRes = CoursierDependencyResolution(
        conf
          .withResolvers(Vector(repo))
          .withAutoScalaLibrary(false)
          .withCache(Files.createTempDirectory("lm-coursier-user-agent").toFile)
      )
      val desc = ModuleDescriptorConfiguration(ModuleID("test", "foo", "1.0"), ModuleInfo("foo"))
        .withDependencies(Vector(ModuleID("org.example", "bar", "1.0").withConfigurations(Some("compile"))))
        .withConfigurations(Vector(Configuration.of("Compile", "compile")))
      depRes.update(depRes.moduleDescriptor(desc), UpdateConfiguration(), UnresolvedWarningConfiguration(), logger)
      agents.asScala.toList
    } finally server.stop(0)
  }

  property("custom user agent is sent to repositories") {
    val agent = s"${CoursierDependencyResolution.coursierUserAgent} sbt/1.99.0 (+https://www.scala-sbt.org/)"
    val agents = requestUserAgents(CoursierConfiguration().withUserAgent(agent))
    assert(agents.nonEmpty)
    assert(agents.forall(_ == agent))
  }

  property("default user agent is sent when none is set") {
    val agents = requestUserAgents(CoursierConfiguration())
    assert(agents.nonEmpty)
    assert(agents.forall(_ == CoursierDependencyResolution.defaultUserAgent))
  }

  property("coursierUserAgent names coursier") {
    assert(CoursierDependencyResolution.coursierUserAgent.startsWith("Coursier/2.1 (+https://github.com/coursier"))
  }

  property("defaultUserAgent is coursier's with an sbt-1 comment") {
    // coursier adds comments of its own, such as "ci" on CI, so only the last one is pinned here
    val expected = CoursierDependencyResolution.coursierUserAgent.stripSuffix(")") + "; sbt-1)"
    assert(CoursierDependencyResolution.defaultUserAgent == expected)
    assert(CoursierDependencyResolution.defaultUserAgent.startsWith("Coursier/2.1 (+https://github.com/coursier; "))
  }
}
