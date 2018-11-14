package coursier

object Helper {

  def checkEmpty(): Boolean =
    SbtCoursierCache.default.isEmpty

}