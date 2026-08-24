package org.sempods.auth.core

import java.net.URI

/**
 * What may appear as a `redirect_uri` at all (RFC 6749 §3.1.2, RFC 8252 §7.3).
 *
 * This is a *shape* check, not an authorization check — whether a given client may use a given
 * address is [ClientRedirectPolicy]'s question. Splitting the two matters: a shape check that
 * reads like an authorization check is how an unvalidated address ends up in a `Location` header.
 *
 * - absolute, with a scheme and a host
 * - no fragment — the component is reserved for the response and must never be registered
 * - `https` anywhere; `http` only for loopback, so a plain-text address cannot be a real target
 */
object RedirectUri {

  /**
   * Addresses that reach only the user's own machine, and may therefore be spoken to over plain
   * `http`.
   *
   * `0.0.0.0` is deliberately **not** here. It is the unspecified bind-all address: something a
   * server listens on, not somewhere a browser is sent. What a browser does with it is
   * platform-dependent, which is exactly what a redirect target may not be.
   */
  private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1")

  /**
   * Addresses whose port is ignored when comparing two of them.
   *
   * A superset of [loopbackHosts], and the difference is the point. Native clients bind an
   * ephemeral port per launch, so RFC 8252 §7.3 requires the server to accept any port for
   * loopback — and a client that registered `0.0.0.0` once must still match itself later, even
   * though `0.0.0.0` may not be *redirected to*. Registration de-duplication and the `/authorize`
   * match must use this same rule, or a client that registers once fails to authorize afterwards.
   *
   * The two sets were separate in the two implementations this replaces, for these two reasons.
   * Merging them widened what may be redirected to; keeping them apart is what the reasons ask for.
   */
  private val portInsensitiveHosts = loopbackHosts + "0.0.0.0"

  // TODO: RFC 8252 private-use schemes (`com.example.app:/cb`) for native clients — no consumer
  //  needs them today, and accepting a scheme nobody validates would widen the surface for free.
  fun isValid(uri: String): Boolean {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return false
    if (!parsed.isAbsolute) return false
    if (parsed.fragment != null) return false
    val scheme = parsed.scheme?.lowercase() ?: return false
    val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    return when (scheme) {
      "https" -> true
      "http" -> host in loopbackHosts
      else -> false
    }
  }

  /** Whether [host] reaches only the user's own machine — the question `isValid` asks of `http`. */
  fun isLoopback(host: String?): Boolean = host?.trim()?.lowercase() in loopbackHosts

  /** Loopback-aware canonical form: the port is dropped for [portInsensitiveHosts], nothing else. */
  fun canonicalize(uri: String): String {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return uri
    val host = parsed.host?.lowercase() ?: return uri
    if (host !in portInsensitiveHosts) return uri
    return runCatching {
      URI(parsed.scheme, parsed.userInfo, host, -1, parsed.path, parsed.query, parsed.fragment).toString()
    }.getOrDefault(uri)
  }
}
