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
   * When it last obtained a token, and null when it never has. A service nobody uses any more
   * shows here, since a service secret does not expire.
   */
  val lastUsedAt: Instant?,
  scopes: Set<String>,
  /**
   * The version [scopes] are at, which [SempodsPodServiceClients.replaceGrants] names. Every change
   * to the grants moves it.
   */
  val grantsVersion: Long,
  /**
   * `registered` for one registered at the pod itself, `provisioned` for one the host operator set
   * up. The owner decides the grants of both; only the first is rotated or removed here.
   */
  val origin: String,
  /**
   * When the pod removes it unless the owner confirms its consent first; null once it is active, and
   * for one the host operator set up.
   */
  val activationExpiresAt: Instant? = null,
) {

  /** The scopes it holds, such as `<context-iri>#read`. Empty for a registration without grants. */
  val scopes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(scopes))

  override fun equals(other: Any?): Boolean =
    other is SempodsServiceClient && other.clientId == clientId && other.clientName == clientName && other.issuedAt == issuedAt &&
      other.lastUsedAt == lastUsedAt && other.scopes == scopes && other.grantsVersion == grantsVersion && other.origin == origin &&
      other.activationExpiresAt == activationExpiresAt

  override fun hashCode(): Int = listOf(clientId, clientName, issuedAt, lastUsedAt, scopes, grantsVersion, origin, activationExpiresAt).hashCode()

  override fun toString(): String =
    "SempodsServiceClient(clientId=$clientId, clientName=$clientName, issuedAt=$issuedAt, lastUsedAt=$lastUsedAt, scopes=$scopes, grantsVersion=$grantsVersion, origin=$origin, activationExpiresAt=$activationExpiresAt)"

  internal companion object {

    @JvmSynthetic
    internal fun of(
      clientId: String,
      clientName: String?,
      issuedAt: Instant,
      lastUsedAt: Instant?,
      scopes: Set<String>,
      grantsVersion: Long,
      origin: String,
      activationExpiresAt: Instant? = null,
    ) = SempodsServiceClient(clientId, clientName, issuedAt, lastUsedAt, scopes, grantsVersion, origin, activationExpiresAt)
  }
}
