package org.sempods.auth.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636), S256 only — `plain` is rejected, per OAuth 2.1.
 *
 * Both halves live here because sempods services are on both sides of the exchange: an
 * authorization server verifying a client's verifier, and a client generating one for the pod it
 * is calling.
 *
 * [verifyS256] compares in constant time. That is not decoration: a byte-by-byte early exit leaks
 * the stored challenge one character per request, and the challenge is what stands between an
 * intercepted authorization code and a token.
 */
object Pkce {

  const val METHOD_S256 = "S256"

  private val random = SecureRandom()

  /** A fresh high-entropy `code_verifier` (RFC 7636 §4.1: 43–128 url-safe characters). */
  fun generateVerifier(): String {
    val bytes = ByteArray(64)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** The S256 `code_challenge` for [verifier]. */
  fun challengeFor(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  /** Whether [verifier] hashes (S256) to [challenge]. */
  fun verifyS256(verifier: String, challenge: String): Boolean =
    Secrets.matches(challengeFor(verifier), challenge)

  /** Whether [method] is a `code_challenge_method` this implementation accepts at all. */
  fun isSupportedMethod(method: String?): Boolean = method == METHOD_S256
}
