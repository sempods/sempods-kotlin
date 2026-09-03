package org.sempods.auth.core

import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
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
 * [verifyS256] does the two things in the order they have to happen: the OAuth SDK says whether the
 * verifier is one RFC 7636 §4.1 allows at all, and this compares it in constant time. The second is
 * not decoration — a byte-by-byte early exit leaks the stored challenge one character per request,
 * and the challenge is what stands between an intercepted authorization code and a token.
 *
 * The split is deliberate and worth keeping in that order wherever a check of ours is stricter than
 * the library's: the SDK answers what the specification says, and what this project adds sits on
 * top of that answer rather than replacing it.
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

  /**
   * Whether [verifier] is a legal `code_verifier` **and** hashes (S256) to [challenge].
   *
   * Both halves answer the same `false`, on purpose. A caller that could tell "malformed" from
   * "does not match" would have an oracle, and RFC 7636 §4.6 asks for one answer either way.
   *
   * The legality half is the SDK's, because the range is the point of the rule rather than a
   * detail of it: 43 characters is what makes a verifier too expensive to guess for an attacker
   * who has intercepted the code and can see the challenge. Accepting a shorter one silently
   * turns PKCE into decoration for exactly the client that got it wrong — and every one of this
   * project's three token endpoints reaches this method, so the check belongs here and not beside
   * each of them.
   */
  fun verifyS256(verifier: String, challenge: String): Boolean =
    isLegalVerifier(verifier) && Secrets.matches(challengeFor(verifier), challenge)

  /**
   * Whether [verifier] is one RFC 7636 §4.1 permits: 43–128 characters from `A-Z a-z 0-9 - . _ ~`.
   *
   * Asked of the OAuth SDK rather than restated here — the constructor is the specification's own
   * rule, and a second reading of it would be a second thing to keep correct. Exposed because an
   * endpoint may want to answer a malformed verifier differently from a wrong one; [verifyS256]
   * deliberately does not.
   */
  fun isLegalVerifier(verifier: String): Boolean = runCatching { CodeVerifier(verifier) }.isSuccess

  /** Whether [method] is a `code_challenge_method` this implementation accepts at all. */
  fun isSupportedMethod(method: String?): Boolean = method == METHOD_S256
}
