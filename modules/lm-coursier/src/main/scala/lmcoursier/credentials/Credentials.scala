package lmcoursier.credentials

import java.io.File

abstract class Credentials extends Serializable

object Credentials {
  def apply(): DirectCredentials = DirectCredentials()
  def apply(host: String, username: String, password: String): DirectCredentials =
    DirectCredentials(host, username, password)
  def apply(host: String, username: String, password: String, realm: Option[String]): DirectCredentials =
    DirectCredentials(host, username, password, realm)
  def apply(host: String, username: String, password: String, realm: String): DirectCredentials =
    DirectCredentials(host, username, password, Option(realm))
  def apply(host: String, username: String, password: String, realm: Option[String], optional: Boolean): DirectCredentials =
    DirectCredentials(host, username, password, realm, optional, matchHost = false, httpsOnly = true)
  def apply(host: String, username: String, password: String, realm: String, optional: Boolean): DirectCredentials =
    DirectCredentials(host, username, password, Option(realm), optional, matchHost = false, httpsOnly = true)

  def apply(f: File): FileCredentials =
    FileCredentials(f.getAbsolutePath)
  def apply(f: File, optional: Boolean): FileCredentials =
    FileCredentials(f.getAbsolutePath, optional)
}
