package org.sempods.client

import java.util.Collections

/**
 * A public client the pod registered (RFC 7591 §3.2.1): an identifier to authorize with, no secret.
 * Members this class does not name are ignored.
 */
class SempodsPublicClient private constructor(
  /** The identifier the pod assigned, `dyn:…` on a sempods pod. */
  val clientId: String,
  /** The name the pod recorded, null when it recorded none. */
  val clientName: String?,
  redirectUris: List<String>,
) {

  /** The redirect URIs the pod recorded, in the order it sent them. */
  val redirectUris: List<String> = Collections.unmodifiableList(ArrayList(redirectUris))

  override fun equals(other: Any?): Boolean =
    other is SempodsPublicClient && other.clientId == clientId && other.clientName == clientName && other.redirectUris == redirectUris

  override fun hashCode(): Int = listOf(clientId, clientName, redirectUris).hashCode()

  override fun toString(): String = "SempodsPublicClient(clientId=$clientId, clientName=$clientName, redirectUris=$redirectUris)"

  internal companion object {

    @JvmSynthetic
    internal fun of(clientId: String, clientName: String?, redirectUris: List<String>): SempodsPublicClient =
      SempodsPublicClient(clientId, clientName, redirectUris)
  }
}
