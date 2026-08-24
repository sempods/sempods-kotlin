package org.sempods.commons.net

import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object UrlUtil {

  fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8)

  fun urlDecode(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8)

  /**
   * `application/x-www-form-urlencoded` decode (UTF-8) that returns `null` on
   * malformed percent-encoding instead of throwing [IllegalArgumentException].
   * Use this on caller-supplied form fields where bad input is a protocol error
   * the endpoint surfaces as 400/401 — e.g. an HTTP Basic header whose username
   * carries a stray `%FF` should produce `invalid_client`, not a 500.
   */
  fun urlDecodeOrNull(value: String): String? {
    return try {
      URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  fun addOrUpdateQueryParameter(uri: URI, param: String, value: String): URI {
    // Parse raw query to avoid splitting on %26-encoded ampersands, then decode each param individually
    val params = queryParams(uri.rawQuery, decodeParams = true).toMutableMap()
    params[param] = value
    // Re-encode all params consistently
    val newRawQuery = params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
    // Build URI string from raw components + new query, use URI.create() to avoid re-encoding
    return URI.create(buildString {
      append(uri.scheme).append("://").append(uri.rawAuthority ?: "")
      append(uri.rawPath ?: "")
      append("?").append(newRawQuery)
      uri.rawFragment?.let { append("#").append(it) }
    })
  }

  /**
   * Returns [uri] with the named query parameter removed entirely. If the
   * parameter is not present, [uri] is returned unchanged. If it was the only
   * query parameter, the resulting URI has no query string at all.
   */
  fun removeQueryParameter(uri: URI, param: String): URI {
    val rawQuery = uri.rawQuery ?: return uri
    val params = queryParams(rawQuery, decodeParams = true).toMutableMap()
    if (params.remove(param) == null) return uri
    val newRawQuery = if (params.isEmpty()) null
      else params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
    return URI.create(buildString {
      append(uri.scheme).append("://").append(uri.rawAuthority ?: "")
      append(uri.rawPath ?: "")
      if (newRawQuery != null) append("?").append(newRawQuery)
      uri.rawFragment?.let { append("#").append(it) }
    })
  }

  /**
   * Returns the fully decoded URI string (scheme://authority/path?decodedQuery).
   * Use this when the URI string will be passed as a value to another URL builder.
   */
  fun toDecodedString(uri: URI): String = buildString {
    append(uri.scheme).append("://").append(uri.authority ?: "")
    append(uri.path ?: "")
    uri.query?.let { append("?").append(it) }
    uri.fragment?.let { append("#").append(it) }
  }

  fun queryParams(query: String?, decodeParams: Boolean = true): Map<String, String> {
    val queryPairs = mutableMapOf<String, String>()
    query?.split("&")?.forEach { param ->
      val idx = param.indexOf("=")
      if (idx == -1) {
        queryPairs[if (decodeParams) urlDecode(param) else param] = ""
      } else {
        val k = param.substring(0, idx)
        val v = param.substring(idx + 1)
        queryPairs[if (decodeParams) urlDecode(k) else k] = if (decodeParams) urlDecode(v) else v
      }
    }
    return queryPairs
  }

  fun queryParams(url: URL): Map<String, String> = queryParams(url.query)
}
