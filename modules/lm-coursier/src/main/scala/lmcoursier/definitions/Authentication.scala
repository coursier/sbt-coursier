package lmcoursier.definitions

import dataclass.data

@data class Authentication(
  user: String,
  password: String,
  optional: Boolean = false,
  realmOpt: Option[String] = None,
  headersOpt: Option[Seq[(String,String)]] = None
) {
  override def toString(): String =
    withPassword("****")
      .productIterator
      .mkString("Authentication(", ", ", ")")
}
