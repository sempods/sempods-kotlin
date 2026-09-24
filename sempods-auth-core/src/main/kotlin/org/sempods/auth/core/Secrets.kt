package org.sempods.auth.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The primitives every random credential needs, in one place: minting one, and comparing one
 * without leaking it.
 *
 * Neither is hard, and that is the problem. Written out by hand at each call site, every copy is a
 * chance to get the entropy or the comparison wrong in a way nothing catches — and reviewing one
 * `ByteArray(32)` against another proves nothing about the next.
 */
object Secrets {

  /**
   * A 256-bit URL-safe secret.
   *
   * 256 bits because these are bearer values: whoever holds one is treated as whoever it was
   * issued to, so guessing one has to be hopeless rather than merely hard. URL-safe because most
   * of them travel in a query parameter, a cookie or a form field.
   */
  fun newSecret(): String {
    val bytes = ByteArray(SECRET_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /**
   * A 144-bit URL-safe identifier.
   *
   * These name a row and are handed out to be quoted back — a `client_id` is the usual one. Holding
   * one authenticates nobody; the request carrying it still has to prove itself. 144 bits keeps two
   * mints from colliding and a row from being found by guessing. Where the value *is* what grants
   * access, [newSecret] is the one to mint.
   */
  fun newOpaqueId(): String {
    val bytes = ByteArray(OPAQUE_ID_BYTES)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /**
   * Whether [presented] equals [expected], in time that does not depend on where they first differ.
   *
   * A byte-by-byte early exit leaks the expected value one character per attempt, and every caller
   * here is comparing something an attacker can retry: a CSRF token, a browser pin, a PKCE
   * challenge. `null` is false rather than an error — an absent value is exactly the case these
   * checks exist to refuse, and treating it as a special outcome invites a caller to forget it.
   */
  fun matches(presented: String?, expected: String?): Boolean {
    if (presented == null || expected == null) return false
    return MessageDigest.isEqual(
      presented.toByteArray(StandardCharsets.UTF_8),
      expected.toByteArray(StandardCharsets.UTF_8),
    )
  }

  /**
   * Whether [candidate] has the shape [newSecret] mints — and nothing else.
   *
   * Not a security check: a well-formed value is not a valid one, and it still has to be looked up
   * and compared. It is a check on where the value may be *put*. These travel as `state`
   * parameters and end up in places with their own grammar — a cookie name, most immediately —
   * and an arbitrary string there is not a wrong answer, it is a rendering failure: a separator or
   * a space makes `Set-Cookie` unbuildable, so a request that should have been a 400 becomes a 500
   * on a public endpoint.
   *
   * So: check the shape before using one structurally, look it up before believing it.
   */
  fun isWellFormed(candidate: String?): Boolean =
    candidate != null && candidate.length == SECRET_CHARS && candidate.all { it in SECRET_ALPHABET }

  private const val SECRET_BYTES = 32
  private const val OPAQUE_ID_BYTES = 18

  /** 32 bytes, base64url, unpadded. */
  private const val SECRET_CHARS = 43
  private val SECRET_ALPHABET =
    ('A'..'Z').toSet() + ('a'..'z').toSet() + ('0'..'9').toSet() + setOf('-', '_')

  private val random = SecureRandom()
}
