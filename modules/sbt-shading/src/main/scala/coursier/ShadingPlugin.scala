package coursier

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipFile

import org.pantsbuild.jarjar.Rule
import org.pantsbuild.jarjar.util.CoursierJarProcessor
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin
import sbt.librarymanagement.DependencyBuilders.OrganizationArtifactName
import sbt.librarymanagement.ScalaModuleInfo

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.xml.{Comment, Elem, Node => XmlNode}
import scala.xml.transform.{RewriteRule, RuleTransformer}

object ShadingPlugin extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = JvmPlugin

  object Rule {
    import org.pantsbuild.jarjar.Rule

    def moveUnder(from: String, to: String): Rule =
      rename(s"$from.**", s"$to.$from.@1")

    def rename(from: String, to: String): Rule = {
      val rule = new Rule
      rule.setPattern(from)
      rule.setResult(to)
      rule
    }
  }

  private def updateIvyXml(file: File, removeDeps: Set[(String, String)]): Unit = {
    val content = scala.xml.XML.loadFile(file)

    val updatedContent = content.copy(child = content.child.map {
      case elem: Elem if elem.label == "dependencies" =>
        elem.copy(child = elem.child.map {
          case elem: Elem if elem.label == "dependency" =>
            (elem.attributes.get("org"), elem.attributes.get("name")) match {
              case (Some(Seq(orgNode)), Some(Seq(nameNode))) =>
                val org = orgNode.text
                val name = nameNode.text
                val remove = removeDeps.contains((org, name))
                if (remove)
                  Comment(
                    s""" shaded dependency $org:$name
                       | $elem
                       |""".stripMargin
                  )
                else
                  elem
              case _ => elem
            }
          case n => n
        })
      case n => n
    })

    val printer = new scala.xml.PrettyPrinter(Int.MaxValue, 2)
    val updatedFileContent = """<?xml version="1.0" encoding="UTF-8"?>""" + '\n' +
      "<!-- hello -->\n" +
      printer.format(updatedContent)
    Files.write(file.toPath, updatedFileContent.getBytes(StandardCharsets.UTF_8))
  }

  private val reallyUpdateIvyXml = Def.task {
    val baseFile = deliverLocal.value
    val log = streams.value.log
    val resolverName = publishLocalConfiguration.value.resolverName.getOrElse(???)
    ivyModule.value.withModule(log) {
      case (ivy, md, _) =>
        val resolver = ivy.getSettings.getResolver(resolverName)
        val artifact = new org.apache.ivy.core.module.descriptor.MDArtifact(md, "ivy", "ivy", "xml", true)
        log.info(s"Writing ivy.xml with shading at $baseFile")
        resolver.publish(artifact, baseFile, true)
    }
  }

  object autoImport {
    // to be set by users
    val shadedModules = settingKey[Set[OrganizationArtifactName]]("")
    val shadingRules = taskKey[Seq[Rule]]("")
    val validNamespaces = taskKey[Set[String]]("")

    // to be set optionally by users
    val shadingVerbose = taskKey[Boolean]("")

    // set by ShadingPlugin
    val shadedJars = taskKey[Seq[File]]("")
    val shadedPackageBin = taskKey[File]("")

    val ShadingRule = Rule

    implicit class ModuleIDShadingOps(private val moduleId: ModuleID) extends AnyVal {
      def module: OrganizationArtifactName =
        if (moduleId.crossVersion == CrossVersion.disabled)
          moduleId.organization % moduleId.name
        else if (moduleId.crossVersion == CrossVersion.binary)
          moduleId.organization %% moduleId.name
        else
          sys.error(s"Don't know how to build a module for cross version ${moduleId.crossVersion}")
    }
  }

  import autoImport._

  private def orgName(modId: ModuleID, scalaModuleInfoOpt: Option[ScalaModuleInfo]): (String, String) = {
    val crossVer = modId.crossVersion
    val transformName = scalaModuleInfoOpt
      .flatMap(scalaInfo => CrossVersion(crossVer, scalaInfo.scalaFullVersion, scalaInfo.scalaBinaryVersion))
      .getOrElse(identity[String] _)
    (modId.organization, transformName(modId.name))
  }

  private def onlyNamespaces(prefixes: Set[String], jar: File, println: String => Unit): Unit = {
    val zf = new ZipFile(jar)
    val unrecognized = zf.entries()
      .asScala
      .map(_.getName)
      .filter { n =>
        !n.startsWith("META-INF/") && !prefixes.exists(n.startsWith)
      }
      .toVector
      .sorted
    for (u <- unrecognized)
      println(s"Unrecognized: $u")
    assert(unrecognized.isEmpty)
  }

  override lazy val projectSettings = Def.settings(

    shadedModules := Set.empty,
    shadingRules := Nil,
    validNamespaces := Set.empty,

    shadingVerbose := false,

    shadedJars := {
      val scalaModuleInfoOpt = scalaModuleInfo.value
      val shadedModules0 = shadedModules.value.map { orgName0 =>
        val modId = orgName0 % "foo"
        orgName(modId, scalaModuleInfoOpt)
      }

      val thisOrgName = orgName(projectID.value, scalaModuleInfoOpt)

      val updateReport = update.value

      val report = updateReport
        .configurations
        .find(_.configuration.name == Compile.name)
        .getOrElse {
          sys.error(s"Configuration ${Compile.name} not found in update report (found configs: ${updateReport.configurations.map(_.configuration.name)})")
        }
      val moduleReports = report
        .modules
        // .filter(!_.evicted)
      val dependencies = for {
        modRep <- moduleReports
        caller <- modRep.callers
      } yield orgName(caller.caller, scalaModuleInfoOpt) -> orgName(modRep.module, scalaModuleInfoOpt)
      val dependencyMap = dependencies
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toMap
      val roots = dependencyMap.getOrElse(thisOrgName, Nil)
      val nonShadedRoots = roots.filterNot(shadedModules0)

      @tailrec
      def nonShaded(newMods: Set[(String, String)], acc: Set[(String, String)]): Set[(String, String)] =
        if (newMods.isEmpty) acc
        else {
          val newMods0 = newMods
            .flatMap(dependencyMap.getOrElse(_, Nil))
            .filter(!newMods.contains(_))
            .filter(!acc.contains(_))
          nonShaded(newMods0, acc ++ newMods)
        }

      val nonShaded0 = nonShaded(nonShadedRoots.toSet, Set.empty)
      val shaded = dependencies.map(_._2).toSet.filterNot(nonShaded0)

      for {
        modRep <- moduleReports
        orgName0 = orgName(modRep.module, scalaModuleInfoOpt)
        if shaded(orgName0)
        (_, f) <- modRep.artifacts
      } yield f
    },

    shadedPackageBin := {
      val log = streams.value.log
      val verbose = shadingVerbose.value
      val rules = shadingRules.value
      val shadedJars0 = shadedJars.value
      val validPrefixes = validNamespaces.value.map(_.replace('.', '/') + "/")
      val orig = packageBin.in(Compile).value
      val dest = orig.getParentFile / s"${orig.getName.stripSuffix(".jar")}-shading.jar"
      if (!dest.exists() || dest.lastModified() < orig.lastModified()) {
        import org.pantsbuild.jarjar.JJProcessor
        import org.pantsbuild.jarjar.util.StandaloneJarProcessor
        val processor = JJProcessor(rules, verbose, false)
        CoursierJarProcessor.run((orig +: shadedJars0).toArray, dest, processor.proc, true)
      }
      onlyNamespaces(validPrefixes, dest, log.error(_))
      dest
    },

    addArtifact(artifact.in(Compile, packageBin), shadedPackageBin),
    // addArtifact(Artifact("ivy", "ivy", "xml"), deliverLocal),

    publishLocal := {
      reallyUpdateIvyXml.dependsOn(publishLocal).value
    },

    deliverLocal := {
      val scalaModuleInfoOpt = scalaModuleInfo.value
      val shadedModules0 = shadedModules.value.map { orgName0 =>
        val modId = orgName0 % "foo"
        orgName(modId, scalaModuleInfoOpt)
      }

      val file = deliverLocal.value
      updateIvyXml(file, shadedModules0)
      file
    },

    pomPostProcess := {
      val previous = pomPostProcess.value
      val scalaModuleInfoOpt = scalaModuleInfo.value

      val shadedModules0 = shadedModules.value.map { orgName0 =>
        val modId = orgName0 % "foo"
        orgName(modId, scalaModuleInfoOpt)
      }

      // Originally based on https://github.com/olafurpg/coursier-small/blob/408528d10cea1694c536f55ba1b023e55af3e0b2/build.sbt#L44-L56
      val transformer = new RuleTransformer(new RewriteRule {
        override def transform(node: XmlNode) = node match {
          case _: Elem if node.label == "dependency" =>
            val org = node.child.find(_.label == "groupId").fold("")(_.text.trim)
            val name = node.child.find(_.label == "artifactId").fold("")(_.text.trim)
            val isShaded = shadedModules0.contains((org, name))
            if (isShaded)
              Comment(
                s""" shaded dependency $org:$name
                   | $node
                   |""".stripMargin
              )
            else
              node
          case _ => node
        }
      })

      node =>
        val node0 = previous(node)
        transformer.transform(node0).head
    }
  )

}
