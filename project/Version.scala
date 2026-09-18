import sbtdynver.GitDescribeOutput

/** Computes the version of the published modules, from the "v*" git tags of this repository. */
object Version {

  /** Version to fall back on when this repository has no "v*" tag at all */
  def noTagVersion = "0.0.1-SNAPSHOT"

  /** Version of the next release after `version`, like `2.1.13` -> `2.1.14-SNAPSHOT` */
  def nextSnapshotVersion(version: String): String = {
    val parts = version.split("[.-]").filter(_.nonEmpty)
    if (parts.length < 3 || !parts(2).forall(_.isDigit))
      sys.error(s"Cannot compute the version following $version, expected a vX.Y.Z-like tag")
    Seq(parts(0), parts(1), (parts(2).toInt + 1).toString).mkString(".") + "-SNAPSHOT"
  }

  /** The tag value if HEAD is right on a "v*" tag, the version following the latest one else */
  def compute(describeOutput: Option[GitDescribeOutput]): String =
    describeOutput match {
      case Some(output) if output.isCleanAfterTag => output.ref.dropPrefix
      case Some(output) if !output.hasNoTags() => nextSnapshotVersion(output.ref.dropPrefix)
      case _ => noTagVersion
    }

}
