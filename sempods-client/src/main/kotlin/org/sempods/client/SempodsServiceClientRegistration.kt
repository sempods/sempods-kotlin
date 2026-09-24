package org.sempods.client

import java.time.Instant

/**
 * A service client the pod just registered for an installation (RFC 7591 §3.2.1), with its secret.
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
) {

  override fun toString(): String =
    "SempodsServiceClientRegistration(clientId=$clientId, clientName=$clientName, issuedAt=$issuedAt, secretExpiresAt=$secretExpiresAt)"

  internal companion object {

    @JvmSynthetic
    internal fun of(clientId: String, clientSecret: String, clientName: String?, issuedAt: Instant, secretExpiresAt: Instant?) =
      SempodsServiceClientRegistration(clientId, clientSecret, clientName, issuedAt, secretExpiresAt)
  }
}
