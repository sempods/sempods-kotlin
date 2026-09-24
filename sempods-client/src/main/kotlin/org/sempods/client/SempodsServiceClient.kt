package org.sempods.client

import java.time.Instant
import java.util.Collections

/** One service client on a pod, as its owner's list shows it. It never carries a secret. */
class SempodsServiceClient private constructor(
  val clientId: String,
  /** The name its registration carries, and null when it has none. */
  val clientName: String?,
  /** When it was registered. */
  val issuedAt: Instant,
  /**
   * When it last obtained a token, and null when it never has. An installation nobody uses any more
   * shows here, since a service secret does not expire.
   */
  val lastUsedAt: Instant?,
  scopes: Set<String>,
  /**
   * `installed` for one an owner installed, `provisioned` for one the host operator set up. The pod
   * lists both and changes only the first.
   */
  val origin: String,
) {

  /** The scopes it holds, such as `<context-iri>#read`. Empty for a registration without grants. */
  val scopes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(scopes))

  override fun equals(other: Any?): Boolean =
    other is SempodsServiceClient && other.clientId == clientId && other.clientName == clientName && other.issuedAt == issuedAt &&
      other.lastUsedAt == lastUsedAt && other.scopes == scopes && other.origin == origin

  override fun hashCode(): Int = listOf(clientId, clientName, issuedAt, lastUsedAt, scopes, origin).hashCode()

  override fun toString(): String =
    "SempodsServiceClient(clientId=$clientId, clientName=$clientName, issuedAt=$issuedAt, lastUsedAt=$lastUsedAt, scopes=$scopes, origin=$origin)"

  internal companion object {

    @JvmSynthetic
    internal fun of(clientId: String, clientName: String?, issuedAt: Instant, lastUsedAt: Instant?, scopes: Set<String>, origin: String) =
      SempodsServiceClient(clientId, clientName, issuedAt, lastUsedAt, scopes, origin)
  }
}
