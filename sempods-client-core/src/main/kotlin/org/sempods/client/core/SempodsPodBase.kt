package org.sempods.client.core

import java.net.URI
import java.util.Locale

/**
 * One pod's base URL, validated once and then trusted.
 *
 * **What a base URL is** is fixed by the specification, and both clauses are enforced here:
 *
 * - [SPS-CORE-019](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-019)
 *   — absolute, `https` on any host and `http` only on a loopback address, no query and no
 *   fragment, and a path that does not end in a slash.
 * - [SPS-CORE-020](https://github.com/sempods/sempods-spec/blob/main/spec/core/index.md#SPS-CORE-020)
 *   — a path with no dot segment, no backslash and no percent-encoded octet.
 *
 * The path clause is the sharper one. A dot segment resolves the pod away on some clients and not
 * on others, so the isolation boundary would depend on whose parser saw it, and the spellings
 * (`%2e%2e`, `.%2e`, a backslash separator) are not a list anyone can close — which is why a
 * percent-encoded octet is refused outright rather than normalised.
 *
 * **A trailing slash is accepted and dropped.** `https://pods.example/alice/` and
 * `https://pods.example/alice` name the same pod, and a consumer reading a base out of
 * configuration should not have to know which spelling this library wanted. **A nested path is
 * preserved**: a deployment serving pods under `https://example.org/pods/alice` keeps both
 * segments, which `URI.resolve` against a base without a trailing slash would silently discard.
 *
 * This is the client-side half. Server configuration validation is
 * [#56](https://github.com/sempods/sempods-kotlin/issues/56); the test vectors are shared through
 * this module's test fixtures so the two cannot drift.
 */
class SempodsPodBase private constructor(
  /** The canonical form: no trailing slash, no query, no fragment. */
  val uri: URI,
) {

  private val prefix: String = uri.toString()

  /**
   * The absolute URI of [podRelativePath] under this base.
   *
   * [podRelativePath] is already percent-encoded by the caller — this appends, it does not encode;
   * [SempodsUrlEncoding.pathSegment] is what encodes one segment. A query may be attached with `?`.
   *
   * Refuses a leading slash, a dot segment and a backslash: each is a way to address something
   * other than what the path reads as, and a session's credential travels with whatever it
   * resolves to.
   */
  fun resolve(podRelativePath: String): URI {
    require(!podRelativePath.startsWith("/")) {
      "A pod-relative path must not start with '/': '$podRelativePath' would address the host root."
    }
    val path = podRelativePath.substringBefore('?')
    require(!path.contains('\\')) { "A pod-relative path must not contain a backslash: '$podRelativePath'" }
    require(path.split('/').none { it == "." || it == ".." }) {
      "A pod-relative path must not contain a dot segment: '$podRelativePath'"
    }
    val separator = if (podRelativePath.isEmpty()) "" else "/"
    return URI("$prefix$separator$podRelativePath")
  }

  /**
   * Whether [target] is this pod, or something under it.
   *
   * Asked again when a request is executed, not only when one is built. A [SempodsRequest] is a
   * plain object holding an absolute URI, so one assembled through session A and executed through
   * session B would otherwise send B's credential to A's pod. Scheme, host and port must match, and
   * the path must be this base or a descendant of it — a sibling that merely shares the prefix as
   * text (`/alice-archive` under `/alice`) is not under it.
   */
  operator fun contains(target: URI): Boolean {
    if (!target.isAbsolute) return false
    if (!target.scheme.equals(uri.scheme, ignoreCase = true)) return false
    if (!(target.host ?: "").equals(uri.host ?: "", ignoreCase = true)) return false
    if (effectivePort(target) != effectivePort(uri)) return false
    val path = target.rawPath ?: ""
    if (path.contains('\\') || path.split('/').any { it == "." || it == ".." }) return false
    val basePath = uri.rawPath ?: ""
    return path == basePath || path.startsWith("$basePath/")
  }

  override fun equals(other: Any?): Boolean = other is SempodsPodBase && other.uri == uri

  override fun hashCode(): Int = uri.hashCode()

  override fun toString(): String = prefix

  companion object {

    /**
     * Binds [baseUrl], or refuses it naming the clause it breaks.
     *
     * @throws IllegalArgumentException when the URL is not a base URL the specification permits.
     */
    @JvmStatic
    fun of(baseUrl: URI): SempodsPodBase {
      val reason = reject(baseUrl)
      require(reason == null) { "'$baseUrl' is not a usable pod base URL: $reason" }
      return SempodsPodBase(canonicalize(baseUrl))
    }

    @JvmStatic
    fun of(baseUrl: String): SempodsPodBase {
      val uri = runCatching { URI(baseUrl) }.getOrNull()
      require(uri != null) { "'$baseUrl' is not a usable pod base URL: not a valid URL" }
      return of(uri)
    }

    /**
     * Why [baseUrl] is not a pod base URL, or `null` when it is one.
     *
     * Public so a consumer can validate configuration without catching an exception, and so
     * server-side configuration validation can assert against the same function rather than a
     * second copy of the rules.
     */
    @JvmStatic
    fun reject(baseUrl: URI): String? {
      if (!baseUrl.isAbsolute) return "must be an absolute URL"
      if (baseUrl.rawQuery != null) return "must not carry a query (SPS-CORE-019)"
      if (baseUrl.rawFragment != null) return "must not carry a fragment (SPS-CORE-019)"
      if (baseUrl.rawUserInfo != null) return "must not contain userinfo (SPS-CORE-019)"
      val scheme = baseUrl.scheme?.lowercase(Locale.ROOT) ?: return "missing scheme"
      val host = baseUrl.host?.lowercase(Locale.ROOT) ?: return "missing host"
      when (scheme) {
        "https" -> {}
        "http" -> if (!isLoopback(host)) {
          return "http is allowed only on a loopback address (SPS-CORE-019)"
        }
        else -> return "scheme must be https, or http on loopback (SPS-CORE-019)"
      }
      val path = baseUrl.rawPath ?: ""
      if (path.contains('%')) return "path must not contain a percent-encoded octet (SPS-CORE-020)"
      if (path.contains('\\')) return "path must not contain a backslash (SPS-CORE-020)"
      if (path.split('/').any { it == "." || it == ".." }) {
        return "path must not contain a dot segment (SPS-CORE-020)"
      }
      return null
    }

    /**
     * The trailing slash is trimmed rather than refused. [SPS-CORE-019] requires the canonical form
     * to have none, and the two spellings name the same pod — so this accepts the equivalent input
     * and stores the form the specification asks for.
     */
    private fun canonicalize(baseUrl: URI): URI {
      val text = baseUrl.toString()
      return if (text.endsWith("/")) URI(text.trimEnd('/')) else baseUrl
    }

    /** RFC 6761 reserves `localhost` and `*.localhost` for loopback; literals are checked as such. */
    private fun isLoopback(host: String): Boolean {
      if (host == "localhost" || host.endsWith(".localhost")) return true
      if (host == "ip6-localhost" || host == "ip6-loopback") return true
      val bare = host.removeSurrounding("[", "]")
      if (bare == "::1") return true
      return bare.startsWith("127.") && bare.split('.').size == 4 &&
        bare.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    private fun effectivePort(uri: URI): Int =
      if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
  }
}
