package coursier.cache.protocol

import java.net.{URL, URLConnection, URLStreamHandler, URLStreamHandlerFactory}

/**
 * Stands in for the handler fm-sbt-s3-resolver registers, so that the ported sbt-coursier/s3
 * test can check that "s3://" resolvers are parsed. Nothing is ever fetched through it.
 */
class S3Handler extends URLStreamHandlerFactory {
  def createURLStreamHandler(protocol: String): URLStreamHandler = new URLStreamHandler {
    protected def openConnection(url: URL): URLConnection =
      throw new UnsupportedOperationException(s"not meant to be opened: $url")
  }
}
