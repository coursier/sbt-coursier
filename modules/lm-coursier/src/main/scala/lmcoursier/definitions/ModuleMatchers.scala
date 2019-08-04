/**
 * This code is generated using [[http://www.scala-sbt.org/contraband/ sbt-contraband]].
 */

// DO NOT EDIT MANUALLY
package lmcoursier.definitions
final class ModuleMatchers private (
  val exclude: Set[lmcoursier.definitions.ModuleMatcher],
  val include: Set[lmcoursier.definitions.ModuleMatcher]) extends Serializable {
  
  
  
  override def equals(o: Any): Boolean = o match {
    case x: ModuleMatchers => (this.exclude == x.exclude) && (this.include == x.include)
    case _ => false
  }
  override def hashCode: Int = {
    37 * (37 * (37 * (17 + "lmcoursier.definitions.ModuleMatchers".##) + exclude.##) + include.##)
  }
  override def toString: String = {
    "ModuleMatchers(" + exclude + ", " + include + ")"
  }
  private[this] def copy(exclude: Set[lmcoursier.definitions.ModuleMatcher] = exclude, include: Set[lmcoursier.definitions.ModuleMatcher] = include): ModuleMatchers = {
    new ModuleMatchers(exclude, include)
  }
  def withExclude(exclude: Set[lmcoursier.definitions.ModuleMatcher]): ModuleMatchers = {
    copy(exclude = exclude)
  }
  def withInclude(include: Set[lmcoursier.definitions.ModuleMatcher]): ModuleMatchers = {
    copy(include = include)
  }
}
object ModuleMatchers {
  /** ModuleMatchers that matches to any modules. */
  def all: ModuleMatchers = ModuleMatchers(Set.empty, Set.empty)
  def apply(exclude: Set[lmcoursier.definitions.ModuleMatcher], include: Set[lmcoursier.definitions.ModuleMatcher]): ModuleMatchers = new ModuleMatchers(exclude, include)
}
