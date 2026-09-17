package org.sempods.client.core

import java.time.Duration

/**
 * A successful token response, as RFC 6749 §5.1 defines it. Members it does not name are ignored.
 *
 * [toString] leaves out [accessToken], so a logged response carries no credential.
 */
class SempodsTokenResponse internal constructor(
  /** The credential to send. */
  val accessToken: String,
  /** How to send [accessToken], for example `Bearer`. RFC 6749 compares it without regard to case. */
  val tokenType: String,
  /** How long [accessToken] is valid from the response on, and null when the server does not say. */
  val expiresIn: Duration?,
  /** The scope the server granted, space-separated as it sent it, and null when it sent none. */
  val scope: String?,
) {

  override fun equals(other: Any?): Boolean =
    other is SempodsTokenResponse && other.accessToken == accessToken && other.tokenType == tokenType &&
      other.expiresIn == expiresIn && other.scope == scope

  override fun hashCode(): Int = listOf(accessToken, tokenType, expiresIn, scope).hashCode()

  override fun toString(): String = "SempodsTokenResponse(tokenType=$tokenType, expiresIn=$expiresIn, scope=$scope)"
}
