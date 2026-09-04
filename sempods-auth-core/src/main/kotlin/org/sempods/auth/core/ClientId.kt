package org.sempods.auth.core

/**
 * An OAuth `client_id`: RFC 6749 Appendix A.1's `*VSCHAR`, and not empty.
 *
 * Checked rather than assumed, because a `client_id` is compared and stored rather than parsed, and
 * `did:web:` identities are presented rather than issued — so whatever a stranger sends is what a
 * pod holds. `*VSCHAR` excludes every character `LogSafeText` escapes, which is what lets the fifty
 * log statements naming a `client_id` interpolate it plainly (`docs/logging.md` §"Three rules").
 */
object ClientId {

  fun isValid(clientId: String): Boolean =
    clientId.isNotEmpty() && clientId.all { it in VSCHAR }

  private val VSCHAR = ' '..'~'
}
