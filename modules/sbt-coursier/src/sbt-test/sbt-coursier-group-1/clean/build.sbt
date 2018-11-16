scalaVersion := "2.11.8"

val checkEmpty = TaskKey[Unit]("checkEmpty")

checkEmpty := {
  assert(coursier.Helper.checkEmpty)
}

val checkNotEmpty = TaskKey[Unit]("checkNotEmpty")

checkNotEmpty := {
  assert(!coursier.Helper.checkEmpty)
}

{
  // Just checking that this class can be found.
  // It should be brought either via sbt-coursier, or via lm-coursier.
  coursier.sbtcoursier.ResolutionRun
  Seq()
}