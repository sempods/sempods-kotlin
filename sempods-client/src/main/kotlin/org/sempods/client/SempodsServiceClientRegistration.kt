package org.sempods.client

import java.time.Instant

/**
 * A service client the pod just registered (RFC 7591 §3.2.1), with its secret.
 * Members this class does not name are ignored.
 *
 * **The pod never answers [clientSecret] again**: store it before anything that can still fail.
 * [toString] leaves it out.
 */
class SempodsServiceClientRegistration private constructor(
  /** The identifier the pod assigned, `svc:…` on a sempods pod. It authenticates with [clientSecret]. */
  val clientId: String,
  /** The secret, for `client_secret_basic` at the token endpoint ([SempodsRequestAuth.clientSecretBasic]). */
  val clientSecret: String,
  /** The name the registration carries, which the grant consent shows the owner. */
  val clientName: String?,
  /** When the pod registered it. The grant consent shows the owner this beside the identifier. */
  val issuedAt: Instant,
  /** When the secret expires, and null for `client_secret_expires_at: 0`, a secret that does not. */
  val secretExpiresAt: Instant?,
  redirectUris: List<String>,
  /**
   * When the pod removes this registration unless the owner grants it contexts first — the sempods
   * member `activation_expires_at`. Null on a pod that does not send it.
   */
  val activationExpiresAt: Instant?,
) {

  /**
   * The redirect URIs registered with the service; empty where none were. The grant consent does not
   * return to these: it returns to the public client that opened it ([SempodsPodServiceClients.grantConsentUrl]).
   */
  val redirectUris: List<String> = java.util.Collections.unmodifiableList(ArrayList(redirectUris))

  override fun toString(): String =
    "SempodsServiceClientRegistration(clientId=$clientId, clientName=$clientName, issuedAt=$issuedAt, " +
      "secretExpiresAt=$secretExpiresAt, redirectUris=$redirectUris, activationExpiresAt=$activationExpiresAt)"

  internal companion object {

    @JvmSynthetic
    internal fun of(
      clientId: String,
      clientSecret: String,
      clientName: String?,
      issuedAt: Instant,
      secretExpiresAt: Instant?,
      redirectUris: List<String> = emptyList(),
      activationExpiresAt: Instant? = null,
    ) = SempodsServiceClientRegistration(clientId, clientSecret, clientName, issuedAt, secretExpiresAt, redirectUris, activationExpiresAt)
  }
}
