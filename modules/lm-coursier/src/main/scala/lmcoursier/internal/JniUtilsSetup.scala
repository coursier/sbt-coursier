package lmcoursier.internal

import java.util.Locale

import lmcoursier.internal.jniutils.LmNativeApi

import scala.util.control.NonFatal

/**
 * Makes coursier use LmNativeApi for its native Windows calls.
 *
 * In lm-coursier-shaded, coursier.jniutils gets shaded, so that the native methods of its
 * DefaultNativeApi can't be bound anymore (JNI ties native functions to class names).
 * LmNativeApi, whose package isn't shaded, has native methods implemented by the same DLL,
 * and needs to be passed to NativeApi.set before coursier uses the latter. See the
 * comment above jniUtilsVersion in build.sbt.
 *
 * Call [[ensureSetup]] from the lm-coursier entry points that can end up calling coursier.
 */
object JniUtilsSetup {

  private lazy val setup: Unit =
    if (isWindows)
      try LmNativeApi.setup()
      catch {
        // Initializing LmNativeApi loads the DLL. If that fails, leave NativeApi alone:
        // coursier then gets errors upon its native calls, and falls back to other means
        // when it can, like it would with the default NativeApi.
        case NonFatal(_) | _: LinkageError =>
      }

  private def isWindows: Boolean =
    sys.props
      .get("os.name")
      .exists(_.toLowerCase(Locale.ROOT).contains("windows"))

  def ensureSetup(): Unit =
    setup
}
