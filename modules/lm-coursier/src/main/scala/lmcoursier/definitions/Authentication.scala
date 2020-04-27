package lmcoursier.definitions

import dataclass._

@data class Authentication(
  user: String,
  password: String,
  optional: Boolean = false,
  realmOpt: Option[String] = None,
  @since
  headers: Seq[(String,String)] = Nil
) {
  override def toString(): String =
    withPassword("****")
    .withHeaders(headers.map((_._1,"****")))
      .productIterator
      .mkString("Authentication(", ", ", ")")
}
