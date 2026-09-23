package coursier.cache

/**
 * Reaches coursier's own User-Agent helpers, which are private to the coursier package.
 *
 * Internal to lm-coursier: this lives in coursier's package only to get at those helpers.
 * Use lmcoursier.CoursierDependencyResolution.coursierUserAgent and defaultUserAgent instead.
 */
object LmCoursierUserAgent {

  /**
   * Coursier's User-Agent, with extra comment tokens after its contact one.
   * The coursier.http.agent Java property overrides it outright, comments included.
   */
  def coursierUserAgent(comments: String*): String =
    CacheUrl.coursierUserAgent(comments: _*)
}
