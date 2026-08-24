package org.sempods.commons.utils

import java.net.URI
import java.util.Base64

/**
 * URIs as path segments: base64url (RFC 4648 §5), unpadded, both directions.
 *
 * This is how an arbitrary URI — including one minted somewhere else entirely — becomes one path
 * segment the System layer can address (`/{pod}/_system/resources/{b64url(uri)}/…`). Percent-encoding
 * would not do: a `%2F` is a slash again by the time a router has read it.
 */
object UriEncodingUtil {

  /**
   * [uri] as an unpadded base64url segment, UTF-8 first so a non-ASCII URI survives.
   *
   * `Base64.getUrlEncoder().withoutPadding()` *is* RFC 4648 §5 — the alphabet with `-` and `_`, and
   * no `=`. This used to encode with the standard alphabet and then replace `+`, `/` and `=` by
   * hand, which produced the same string by a longer route and made the encoder look like a
   * convention of this codebase rather than the standard it implements.
   */
  fun encodeUriToUrlSafeBase64(uri: URI): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(uri.toString().toByteArray(Charsets.UTF_8))

  private val STRICT_BASE64URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")

  /**
   * The inverse of [encodeUriToUrlSafeBase64], and strict about it: rejects the standard Base64
   * alphabet (`+`, `/`), rejects padding (`=`), rejects any character outside `[A-Za-z0-9_-]`, and
   * rejects lengths that cannot be valid base64url-without-padding (length mod 4 == 1).
   *
   * **Strictness is the point, not caution.** Several encodings decode to the same URI, so a
   * tolerant decoder gives a caller more than one spelling for the same resource — which is a way
   * to smuggle a URI variant past anything that compares path segments, and a way to mint two
   * cache keys for one thing. Only what [encodeUriToUrlSafeBase64] produces is accepted.
   */
  fun decodeUrlSafeBase64ToUriStrict(encodedValue: String?): URI {
    if (encodedValue.isNullOrEmpty()) {
      throw IllegalArgumentException("base64url segment must not be empty")
    }
    if (!STRICT_BASE64URL_PATTERN.matches(encodedValue)) {
      throw IllegalArgumentException(
        "base64url segment contains characters outside the RFC 4648 §5 alphabet " +
          "(allowed: A-Z a-z 0-9 - _; no padding)"
      )
    }
    if (encodedValue.length % 4 == 1) {
      throw IllegalArgumentException("base64url segment has invalid length (length mod 4 == 1)")
    }

    val decodedBytes = try {
      Base64.getUrlDecoder().decode(encodedValue)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("base64url segment is not decodable: ${e.message}", e)
    }
    val uriString = String(decodedBytes, Charsets.UTF_8)
    return try {
      URI.create(uriString)
    } catch (e: Exception) {
      throw IllegalArgumentException("base64url segment does not decode to a valid URI: ${e.message}", e)
    }
  }
}
