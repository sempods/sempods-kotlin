package org.sempods.commons.net

/**
 * HTTP Bearer authentication helper (RFC 6750), the counterpart to [BasicAuth].
 *
 * Exists because the scheme name is **case-insensitive** (RFC 7235 §2.1) and hand-rolled
 * `startsWith("Bearer ")` checks are not: a gateway or client that normalises the scheme to
 * `bearer` or `BEARER` would be rejected despite presenting a valid credential. Every caller
 * parsing an `Authorization: Bearer …` header should go through here so there is one answer to
 * "is this header well-formed" instead of one per call site.
 */
object BearerAuth {

  private const val SCHEME = "Bearer "

  /**
   * Extracts the token from an `Authorization: Bearer <token>` header.
   *
   * The scheme is matched case-insensitively; the token itself is returned verbatim, since it is
   * compared against a stored value byte for byte. Returns `null` for anything unusable — absent
   * header, a different scheme, a missing separator (`Bearerabc` is not a Bearer header), or an
   * empty token — so callers turn it into the protocol's own error rather than a 500.
   */
  fun parse(header: String?): String? {
    val trimmed = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!trimmed.regionMatches(0, SCHEME, 0, SCHEME.length, ignoreCase = true)) {
      return null
    }
    return trimmed.substring(SCHEME.length).trim().takeIf { it.isNotEmpty() }
  }
}
