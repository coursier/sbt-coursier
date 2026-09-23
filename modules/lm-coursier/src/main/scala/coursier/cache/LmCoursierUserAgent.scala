package coursier.cache

/**
 * Reaches coursier's default User-Agent, which is private to the coursier package.
 *
 * Internal to lm-coursier: this lives in coursier's package only to get at that value.
 * Use lmcoursier.CoursierDependencyResolution.coursierUserAgent and defaultUserAgent instead.
 */
object LmCoursierUserAgent {

  /** Coursier's own User-Agent, overridden by the coursier.http.agent Java property. */
  def coursierUserAgent: String =
    CacheUrl.defaultUserAgent
}
