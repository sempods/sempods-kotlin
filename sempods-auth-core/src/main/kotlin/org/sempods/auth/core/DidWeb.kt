package org.sempods.auth.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `did:web` as a client identifier — the static-client model, no registration call.
 *
 * A service with a stable HTTPS origin does not need to register: its identity *is* its origin.
 * `did:web:example.org` may only redirect to `https://example.org/…`, so a token can never be
 * delivered anywhere but the origin the identifier names.
 *
 * The property that makes this cheap is what it does **not** do: nothing is fetched. The
 * host-and-port match is the whole check, so there is no SSRF surface, no cache, and no
 * third-party availability in the login path. A client-metadata document that had to be
 * dereferenced would buy no additional security here and would cost all three.
 *
 * W3C did:web encodes a non-default port by percent-escaping the colon, and further path
 * segments as colon-separated components.
 */
object DidWeb {

  const val PREFIX = "did:web:"

  /**
   * What a path segment handed to [clientId] may contain: the DID syntax's `idchar` less
   * `pct-encoded`, which is `ALPHA / DIGIT / "." / "-" / "_"`.
   *
   * Narrow because both wider readings are wrong. Anything outside plain ASCII — an accent, a
   * newline — is not a DID at all, and this repository would go on to refuse it as a `client_id`
   * (`ClientId.isValid` is RFC 6749's `*VSCHAR`), so minting it produces an identity that cannot
   * authorize anywhere: a caller learns that at `/authorize` instead of here. And `pct-encoded`,
   * which the DID grammar does allow, is left out on purpose: [targetOf] percent-decodes every
   * segment, so an encoded one would come back as something other than what was minted, and the
   * identifier would cover a subtree nobody named. A caller needing a character outside this set
   * needs a different segment, not an escape.
   */
  val SEGMENT_CHARS = Regex("^[A-Za-z0-9._-]+$")

  /** What a `did:web` identifier permits: an origin, and optionally a path prefix below it. */
  data class Target(val host: String, val port: Int, val pathPrefix: String) {

    /**
     * Whether [uri] lies within what this identifier covers.
     *
     * Matching on **path segments**, not on a string prefix. `did:web:example.org:mcp` covers
     * `/mcp` and `/mcp/cb` and must not cover `/mcp-other/cb` — the two share five characters and
     * nothing else. sempods-spec `spec/core/grants.md` states the same rule for the `manage` scope and adds why it
     * is load-bearing there: an implementation using raw `startsWith` is wrong, because the point
     * of the prefix is a tree and `-` is not a separator.
     *
     * Both callers ask through here rather than comparing themselves, so the pod server and the
     * identity service cannot answer this differently.
     */
    fun covers(uri: URI): Boolean {
      val uriHost = uri.host?.trim()?.lowercase() ?: return false
      if (uriHost != host || normalizedPort(uri) != port) return false
      if (pathPrefix == "/") return true
      val path = uri.path.orEmpty()
      return path == pathPrefix || path.startsWith("$pathPrefix/")
    }
  }

  /**
   * The `did:web:` identifier for a service reachable at [baseUrl], optionally narrowed to a
   * subtree of it by [pathSegments]: `clientId("https://mcp.example.org", listOf("cron-agent"))` is
   * `did:web:mcp.example.org:cron-agent`, which covers `https://mcp.example.org/cron-agent/…` and
   * nothing else on that host. That is how one service holds more than one identity — the hosted
   * MCP service gives each named profile its own, so a pod tells them apart.
   *
   * **The caller owes the DID document where the method's read algorithm looks**, which is
   * `<baseUrl>/<segments…>/did.json` — `/.well-known` is inserted only where the identifier leaves
   * no path, so the host-only form is the one served at `<baseUrl>/.well-known/did.json`. Which is
   * why the prefix is stated here rather than read off [baseUrl]: a base URL that carries a path is
   * still refused, because a caller passing one has said nothing about where it serves anything,
   * and an identifier whose document is not where the identifier says it is fails every party that
   * dereferences it. A sempods pod does not (the origin match is the whole check), but `did:web`
   * permits it.
   *
   * @throws IllegalArgumentException if [baseUrl] is not an absolute host-root http(s) URL, or a
   *   segment is not [SEGMENT_CHARS].
   */
  fun clientId(baseUrl: String, pathSegments: List<String> = emptyList()): String {
    val uri = runCatching { URI(baseUrl.trimEnd('/')) }.getOrNull()
      ?: throw IllegalArgumentException("service base URL is not a URL: $baseUrl")
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "service base URL must be absolute http(s): $baseUrl" }
    require(uri.rawQuery == null && uri.rawFragment == null) { "service base URL must not carry a query or fragment: $baseUrl" }
    val host = uri.host?.lowercase() ?: throw IllegalArgumentException("service base URL has no host: $baseUrl")

    val rawPath = uri.rawPath.orEmpty()
    require(rawPath.isEmpty() || rawPath == "/") {
      "service base URL must be host-root for a did:web static client (path prefix '$rawPath' is not supported): $baseUrl"
    }
    pathSegments.forEach { segment ->
      require(SEGMENT_CHARS.matches(segment)) {
        "did:web path segment must be one or more of A-Z a-z 0-9 . - _ : '$segment'"
      }
    }

    val authority = when (val port = uri.port) {
      -1, defaultPort(scheme) -> host
      else -> host + URLEncoder.encode(":", Charsets.UTF_8) + port
    }
    return PREFIX + authority + pathSegments.joinToString("") { ":$it" }
  }

  /** `null` when [clientId] is not a `did:web` identifier, or is malformed. */
  fun targetOf(clientId: String): Target? {
    if (!clientId.startsWith(PREFIX)) return null
    val segments = clientId.removePrefix(PREFIX).split(":")
    val hostRaw = segments.firstOrNull()?.let { decode(it) }?.trim()?.takeIf { it.isNotBlank() } ?: return null
    // Parsed as a URL rather than split by hand so that `host%3A8443` yields host and port the
    // same way the rest of the stack reads them.
    val hostUri = runCatching { URI("https://$hostRaw") }.getOrNull() ?: return null
    val host = hostUri.host?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val pathSegments = segments.drop(1).map { decode(it).trim().trim('/') }.filter { it.isNotBlank() }
    return Target(
      host = host,
      port = normalizedPort(hostUri),
      pathPrefix = if (pathSegments.isEmpty()) "/" else "/" + pathSegments.joinToString("/"),
    )
  }

  /**
   * The minimal DID document a `did:web` client serves — at `/.well-known/did.json` for a
   * host-only identifier, and at `/<path…>/did.json` for a path-scoped one.
   */
  fun document(clientId: String): Map<String, Any> = linkedMapOf(
    "@context" to listOf("https://www.w3.org/ns/did/v1"),
    "id" to clientId,
  )

  /** The port a URI addresses, with the scheme default filled in — so `:443` and absent compare equal. */
  fun normalizedPort(uri: URI): Int = if (uri.port != -1) uri.port else defaultPort(uri.scheme?.lowercase())

  private fun defaultPort(scheme: String?): Int = when (scheme) {
    "http" -> 80
    "https" -> 443
    else -> -1
  }

  private fun decode(segment: String): String =
    runCatching { URLDecoder.decode(segment, Charsets.UTF_8) }.getOrDefault(segment)
}
