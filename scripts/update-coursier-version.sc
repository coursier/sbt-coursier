#!/usr/bin/env -S scala-cli shebang

//> using scala 3.3.8
//> using dep com.lihaoyi::os-lib:0.11.8
//> using dep com.lihaoyi::requests:0.9.3
//> using dep org.scala-lang.modules::scala-xml:2.5.0
// else requests warns it can't clean up its HTTP client threads, on JVMs < 21
//> using javaOpt --add-opens=java.net.http/jdk.internal.net.http=ALL-UNNAMED

// Checks on Maven Central whether a newer version of the main coursier module
// (io.get-coursier:coursier_3) is available, and if so, bumps the coursier
// version this build uses.
//
// coursier_3 is the module to look at, even though this build is an sbt 1.x
// plugin depending on coursier_2.12: coursier_3 is published for every coursier
// release, while coursier_2.12 can lag behind or not be published at all for a
// given one. That's fine as long as coursierVersion0 in build.sbt is "SNAPSHOT",
// as we then build coursier from sources at COURSIER_TAG, getting 2.12 artifacts
// out of that build rather than from Maven Central. Override SCALA_BINARY_VERSION
// to check another variant (say "2.12", were the build to depend on a released
// coursier again).
//
// That version lives in two places:
// - coursierVersion0 in build.sbt, the version the build depends on - it's only
//   updated when it holds an actual version, that is when it isn't "SNAPSHOT"
//   (see the comments there and in scripts/publish-local-coursier.sh)
// - COURSIER_TAG in scripts/publish-local-coursier.sh, the coursier tag the
//   modules the build depends on are built from
// Both are kept in sync, so that this works whether we depend on modules from
// Maven Central, or on ones built from sources.
//
// Prints the version it updated to on stdout, and nothing at all if we're already
// up-to-date. Anything that prevents us from getting the latest version (metadata
// that can't be downloaded or parsed, no version in it, a version that doesn't
// look like one…), and anything that prevents us from updating the files above
// (their content changed…), is an error: we exit with a non-zero exit code, so
// that the job running this fails rather than silently doing nothing.
//
// Run weekly from .github/workflows/update-coursier-version.yml.

import scala.util.control.NonFatal
import scala.util.matching.Regex
import scala.xml.XML

def fail(message: String): Nothing =
  System.err.println(s"Error: $message")
  sys.exit(1)

val scalaBinaryVersion = sys.env.getOrElse("SCALA_BINARY_VERSION", "3")
val metadataUrl = sys.env.getOrElse(
  "METADATA_URL",
  s"https://repo1.maven.org/maven2/io/get-coursier/coursier_$scalaBinaryVersion/maven-metadata.xml"
)

// the directory this is run from, or the closest parent of it, having build.sbt in it
def repoRoot(dir: os.Path): os.Path =
  if os.exists(dir / "build.sbt") then dir
  else if dir == os.root then fail("no build.sbt found in the current directory or its parents")
  else repoRoot(dir / os.up)

val root          = repoRoot(os.pwd)
val buildSbt      = root / "build.sbt"
val publishScript = root / "scripts" / "publish-local-coursier.sh"

val versionLine = """^def coursierVersion0 = "(.*)"$""".r
val tagLine     = """^COURSIER_TAG="\$\{COURSIER_TAG:-(.*)\}"$""".r

def fetch(url: String): String =
  // local files are accepted too, handy to try this script out
  if url.startsWith("file:") then
    val path = try os.Path(java.nio.file.Paths.get(java.net.URI.create(url)))
    catch case NonFatal(e) => fail(s"invalid file URL $url: $e")
    if !os.isFile(path) then fail(s"$path not found")
    os.read(path)
  else
    val response =
      try requests.get(url, readTimeout = 60000, connectTimeout = 30000, check = false)
      catch case NonFatal(e) => fail(s"couldn't get $url: $e")
    if response.statusCode != 200 then
      fail(s"got status code ${response.statusCode} when getting $url")
    response.text()

def latestVersion(): String =
  val metadata =
    try XML.loadString(fetch(metadataUrl))
    catch case NonFatal(e) => fail(s"couldn't parse $metadataUrl: $e")
  // <release> is the latest version pushed there, as computed by the tooling that
  // pushed it. The <versions> list is of no use to compute it ourselves, it isn't
  // sorted in any way we could rely on, and even has non-version entries in it
  // (like "interface-v1.0.29").
  val release = (metadata \ "versioning" \ "release").text.trim
  if release.isEmpty then fail(s"no release version found in $metadataUrl")
  if !release.matches("""[0-9][0-9A-Za-z.\-+]*""") then
    fail(s"$release, found in $metadataUrl, doesn't look like a version")
  val versions = (metadata \ "versioning" \ "versions" \ "version").map(_.text.trim)
  if !versions.contains(release) then
    fail(s"release version $release isn't in the version list of $metadataUrl")
  release

def currentValue(path: os.Path, regex: Regex): String =
  os.read.lines(path).collect { case regex(value) => value } match
    case Seq(value) => value
    case Seq()      => fail(s"no line matching $regex found in $path")
    case _          => fail(s"several lines matching $regex found in $path")

def replaceLine(path: os.Path, regex: Regex, newLine: String): Unit =
  // splitting rather than os.read.lines, so that we keep the trailing new line
  val lines   = os.read(path).split("\n", -1)
  val updated = lines.map { case line @ regex(_) => newLine; case line => line }
  os.write.over(path, updated.mkString("\n"))

val latest         = latestVersion()
val currentVersion = currentValue(buildSbt, versionLine)
val currentTag     = currentValue(publishScript, tagLine)
// when the build depends on a snapshot, the version it actually gets is the one
// built from the coursier tag pinned in the publishing script
val current = if currentVersion == "SNAPSHOT" then currentTag.stripPrefix("v") else currentVersion

if current == latest then System.err.println(s"Already using the latest coursier version ($current)")
else
  System.err.println(s"Updating coursier from $current to $latest")
  if currentVersion != "SNAPSHOT" then
    replaceLine(buildSbt, versionLine, s"""def coursierVersion0 = "$latest"""")
  replaceLine(publishScript, tagLine, "COURSIER_TAG=\"${COURSIER_TAG:-v" + latest + "}\"")
  // ensure the files we just rewrote are still in the shape we expect, so that we
  // never open a pull request with no or bogus changes in it
  if currentValue(publishScript, tagLine) != s"v$latest" ||
    (currentVersion != "SNAPSHOT" && currentValue(buildSbt, versionLine) != latest)
  then fail(s"failed to update the coursier version in $buildSbt / $publishScript")
  println(latest)
