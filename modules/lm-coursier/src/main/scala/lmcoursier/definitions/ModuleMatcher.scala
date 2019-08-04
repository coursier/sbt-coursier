/**
 * This code is generated using [[http://www.scala-sbt.org/contraband/ sbt-contraband]].
 */

// DO NOT EDIT MANUALLY
package lmcoursier.definitions
final class ModuleMatcher private (
  val matcher: lmcoursier.definitions.Module) extends Serializable {
  
  
  
  override def equals(o: Any): Boolean = o match {
    case x: ModuleMatcher => (this.matcher == x.matcher)
    case _ => false
  }
  override def hashCode: Int = {
    37 * (37 * (17 + "lmcoursier.definitions.ModuleMatcher".##) + matcher.##)
  }
  override def toString: String = {
    "ModuleMatcher(" + matcher + ")"
  }
  private[this] def copy(matcher: lmcoursier.definitions.Module = matcher): ModuleMatcher = {
    new ModuleMatcher(matcher)
  }
  def withMatcher(matcher: lmcoursier.definitions.Module): ModuleMatcher = {
    copy(matcher = matcher)
  }
}
object ModuleMatcher {
  /** ModuleMatcher that matches to any modules. */
  def all: ModuleMatcher = ModuleMatcher(Module(Organization("*"), ModuleName("*"), Map.empty))
  def apply(matcher: lmcoursier.definitions.Module): ModuleMatcher = new ModuleMatcher(matcher)
}
