package sbt.librarymanagement
package coursier

trait CoursierLibraryManagementCodec
    extends sjsonnew.BasicJsonProtocol
    with LibraryManagementCodec
    // with sbt.internal.librarymanagement.formats.GlobalLockFormat
    with sbt.internal.librarymanagement.formats.LoggerFormat
    with sbt.librarymanagement.ResolverFormats
    with CoursierConfigurationFormats

object CoursierLibraryManagementCodec extends CoursierLibraryManagementCodec
