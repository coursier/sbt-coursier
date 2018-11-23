package coursier.sbtcoursiershared

import java.io.{File, OutputStreamWriter}

import coursier.{Cache, Credentials, TermDisplay}
import coursier.core.{Configuration, Project, Publication}
import coursier.lmcoursier.{CreateLogger, FallbackDependency, SbtCoursierCache}
import sbt.{AutoPlugin, Classpaths, Compile, Setting, TaskKey, Test, settingKey, taskKey}
import sbt.Keys._
import sbt.librarymanagement.{Resolver, URLRepository}

object SbtCoursierShared extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val coursierGenerateIvyXml = settingKey[Boolean]("")
    val coursierProject = TaskKey[Project]("coursier-project")
    val coursierInterProjectDependencies = TaskKey[Seq[Project]]("coursier-inter-project-dependencies", "Projects the current project depends on, possibly transitively")
    val coursierPublications = TaskKey[Seq[(Configuration, Publication)]]("coursier-publications")

    val coursierKeepPreloaded = settingKey[Boolean]("Whether to take into account sbt preloaded repositories or not")
    val coursierReorderResolvers = settingKey[Boolean](
      "Whether resolvers should be re-ordered so that typically slow ones are given a lower priority"
    )
    val coursierResolvers = taskKey[Seq[Resolver]]("")
    val coursierRecursiveResolvers = taskKey[Seq[Resolver]]("Resolvers of the current project, plus those of all from its inter-dependency projects")
    val coursierSbtResolvers = taskKey[Seq[Resolver]]("")

    val coursierFallbackDependencies = taskKey[Seq[FallbackDependency]]("")

    val mavenProfiles = settingKey[Set[String]]("")

    val coursierUseSbtCredentials = settingKey[Boolean]("")
    val coursierCredentials = taskKey[Map[String, Credentials]]("")

    val coursierCreateLogger = taskKey[CreateLogger]("")

    val coursierCache = settingKey[File]("")

    type CoursierCreateLogger = coursier.lmcoursier.CreateLogger
    val CoursierCreateLogger = coursier.lmcoursier.CreateLogger

    val sbtCoursierVersion = Properties.version
  }

  import autoImport._

  def publicationsSetting(packageConfigs: Seq[(sbt.Configuration, Configuration)]): Setting[_] =
    coursierPublications := ArtifactsTasks.coursierPublicationsTask(packageConfigs: _*).value

  override def buildSettings: Seq[Setting[_]] =
    Seq(
      coursierReorderResolvers := true,
      coursierKeepPreloaded := false,
      coursierUseSbtCredentials := true,
      coursierCredentials := Map.empty,
      coursierCreateLogger := CreateLogger { () => new TermDisplay(new OutputStreamWriter(System.err)) },
      coursierCache := Cache.default
    )

  private val pluginIvySnapshotsBase = Resolver.SbtRepositoryRoot.stripSuffix("/") + "/ivy-snapshots"

  override def projectSettings = settings(pubSettings = true)

  def settings(pubSettings: Boolean) =
    Seq[Setting[_]](
      clean := {
        val noWarningPlz = clean.value
        SbtCoursierCache.default.clear()
      },
      coursierGenerateIvyXml := true,
      coursierProject := InputsTasks.coursierProjectTask.value,
      coursierInterProjectDependencies := InputsTasks.coursierInterProjectDependenciesTask.value
    ) ++ {
      if (pubSettings)
        Seq(
          publicationsSetting(Seq(Compile, Test).map(c => c -> Configuration(c.name)))
        )
      else
        Nil
    } ++ Seq(
      // Tests artifacts from Maven repositories are given this type.
      // Adding it here so that these work straightaway.
      classpathTypes += "test-jar", // FIXME Should this go in buildSettings?
      coursierResolvers := RepositoriesTasks.coursierResolversTask.value,
      coursierRecursiveResolvers := RepositoriesTasks.coursierRecursiveResolversTask.value,
      coursierSbtResolvers := {

        // TODO Add docker-based integration test for that, see https://github.com/coursier/coursier/issues/632

        val resolvers =
          sbt.Classpaths.bootRepositories(appConfiguration.value).toSeq.flatten ++ // required because of the hack above it seems
            externalResolvers.in(updateSbtClassifiers).value

        val pluginIvySnapshotsFound = resolvers.exists {
          case repo: URLRepository =>
            repo
              .patterns
              .artifactPatterns
              .headOption
              .exists(_.startsWith(pluginIvySnapshotsBase))
          case _ => false
        }

        val resolvers0 =
          if (pluginIvySnapshotsFound && !resolvers.contains(Classpaths.sbtPluginReleases))
            resolvers :+ Classpaths.sbtPluginReleases
          else
            resolvers

        if (SbtCoursierShared.autoImport.coursierKeepPreloaded.value)
          resolvers0
        else
          resolvers0.filter { r =>
            !r.name.startsWith("local-preloaded")
          }
      },
      coursierFallbackDependencies := InputsTasks.coursierFallbackDependenciesTask.value,
      ivyConfigurations := {
        val confs = ivyConfigurations.value
        val names = confs.map(_.name).toSet

        // Yes, adding those back in sbt 1.0. Can't distinguish between config test (whose jars with classifier tests ought to
        // be added), and sources / docs else (if their JARs are in compile, they would get added too then).

        val extraSources =
          if (names("sources"))
            None
          else
            Some(
              sbt.Configuration.of(
                id = "Sources",
                name = "sources",
                description = "",
                isPublic = true,
                extendsConfigs = Vector.empty,
                transitive = false
              )
            )

        val extraDocs =
          if (names("docs"))
            None
          else
            Some(
              sbt.Configuration.of(
                id = "Docs",
                name = "docs",
                description = "",
                isPublic = true,
                extendsConfigs = Vector.empty,
                transitive = false
              )
            )

        confs ++ extraSources.toSeq ++ extraDocs.toSeq
      },
      mavenProfiles := Set.empty
    ) ++ {
      if (pubSettings)
        IvyXml.generateIvyXmlSettings()
      else
        Nil
    }

}
