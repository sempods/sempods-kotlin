package org.sempods.commons.net

import java.util.Base64

/**
 * Username + password pair as carried by an `Authorization: Basic` header.
 * Strings are already form-decoded — see [BasicAuth.parse] for the encoding
 * contract the parser enforces.
 */
data class BasicCredentials(val username: String, val password: String)

/**
 * HTTP Basic authentication helpers (RFC 7617). The current focus is OAuth
 * `client_secret_basic` per RFC 6749 §2.3.1, but the parser itself is grant-
 * agnostic — any caller that needs to read a `Basic` header against UTF-8
 * form-encoded credentials can use it.
 */
object BasicAuth {

  /**
   * Decode an `Authorization: Basic <base64(formEncode(user):formEncode(secret))>`
   * header. The form-decode step on each half matters: without it credentials
   * containing reserved characters (`:`, space, `+`, `%`, …) would never match
   * a persisted value. Returns `null` for any malformed input — callers turn
   * that into the protocol's error (e.g. OAuth `invalid_client`) rather than
   * letting it surface as 500.
   *
   * Empty username, empty-after-decode password, missing colon separator, bad
   * base64, malformed percent-encoding, and a missing or non-`Basic` scheme
   * all map to `null`.
   */
  fun parse(header: String?): BasicCredentials? {
    val trimmed = header?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (!trimmed.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return null
    val encoded = trimmed.substring(6).trim().takeIf { it.isNotBlank() } ?: return null
    val decoded = try {
      Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      return null
    }
    val colon = decoded.indexOf(':')
    if (colon < 0) return null
    val rawUsername = decoded.substring(0, colon)
    val rawPassword = decoded.substring(colon + 1)
    val username = UrlUtil.urlDecodeOrNull(rawUsername) ?: return null
    val password = UrlUtil.urlDecodeOrNull(rawPassword) ?: return null
    if (username.isBlank() || password.isEmpty()) return null
    return BasicCredentials(username = username, password = password)
  }
}
