package coursier.sbtcoursiershared

import sbt._

object Structure {
  import sbt.Project.structure

  def transitiveInterDependencies(
      state: State,
      projectRef: ProjectRef
  ): Seq[ProjectRef] = {
    def dependencies(map: Map[ProjectRef, Seq[ProjectRef]], id: ProjectRef): Set[ProjectRef] = {
      def helper(map: Map[ProjectRef, Seq[ProjectRef]], acc: Set[ProjectRef]): Set[ProjectRef] =
        if (acc.exists(map.contains)) {
          val (kept, rem) = map.partition { case (k, _) => acc(k) }
          helper(rem, acc ++ kept.valuesIterator.flatten)
        } else
          acc
      helper(map - id, map.getOrElse(id, Nil).toSet)
    }
    val allProjectsDeps: Map[ProjectRef, Seq[ProjectRef]] =
      (for {
        (p, ref) <- allProjectPairs(state)
      } yield ref -> p.dependencies.map(_.project)).toMap
    val deps = dependencies(allProjectsDeps.toMap, projectRef)
    structure(state).allProjectRefs.filter(p => deps(p))
  }

  private def allProjectPairs(state: State): Seq[(ResolvedProject, ProjectRef)] =
    for {
      (build, unit) <- structure(state).units.toSeq
      p: ResolvedProject <- unit.defined.values.toSeq
    } yield (p, ProjectRef(build, p.id))

  // vv things from sbt-structure vv

  implicit class `enrich SettingKey`[T](key: SettingKey[T]) {
    def find(state: State): Option[T] =
      key.get(structure(state).data)

    def get(state: State): T =
      find(state).get

    def getOrElse(state: State, default: => T): T =
      find(state).getOrElse(default)
  }

  // ^^ things from sbt-structure ^^
}
