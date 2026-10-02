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
   * Use this on caller-supplied text where bad input is a protocol error the
   * endpoint surfaces as 400/401 — a stray `%FF` should produce that error, not a 500.
   */
  fun urlDecodeOrNull(value: String): String? {
    return try {
      URLDecoder.decode(value, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /**
   * Returns [uri] with [param] set to [value]: every pair named [param] is removed and one is
   * appended. The rest of the query stays exactly as it was written — repeated names, empty
   * components, order and encoding included — because it belongs to whoever wrote the address
   * (RFC 6749 §3.1.2 has an authorization server keep a redirect URI's query).
   */
  fun addOrUpdateQueryParameter(uri: URI, param: String, value: String): URI {
    val pair = "${urlEncode(param)}=${urlEncode(value)}"
    val kept = uri.rawQuery?.split('&')?.filterNot { isNamed(it, param) }?.joinToString("&")
    return withRawQuery(uri, if (kept.isNullOrEmpty()) pair else "$kept&$pair")
  }

  /**
   * Returns [uri] with every pair named [param] removed, and the rest of the query as it was
   * written. If none is named [param], [uri] is returned unchanged; if nothing is left, the result
   * has no query string at all.
   */
  fun removeQueryParameter(uri: URI, param: String): URI {
    val components = uri.rawQuery?.split('&') ?: return uri
    val kept = components.filterNot { isNamed(it, param) }
    if (kept.size == components.size) return uri
    return withRawQuery(uri, kept.joinToString("&").ifEmpty { null })
  }

  /** Whether the raw query component [pair] is named [param]; a name that does not decode is not. */
  private fun isNamed(pair: String, param: String): Boolean {
    val name = pair.substringBefore('=')
    return (urlDecodeOrNull(name) ?: name) == param
  }

  /** [uri] with [rawQuery] as its query, built from raw components so nothing is re-encoded. */
  private fun withRawQuery(uri: URI, rawQuery: String?): URI = URI.create(buildString {
    append(uri.scheme).append("://").append(uri.rawAuthority ?: "")
    append(uri.rawPath ?: "")
    rawQuery?.let { append("?").append(it) }
    uri.rawFragment?.let { append("#").append(it) }
  })

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
