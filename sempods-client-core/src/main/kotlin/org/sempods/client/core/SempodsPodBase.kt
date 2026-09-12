package org.sempods.client.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * segments.
 *
 * This is the client-side half. Server configuration validation is
 * [#56](https://github.com/sempods/sempods-kotlin/issues/56); the test vectors are shared through
 * this module's test fixtures so the two cannot drift.
 */
class SempodsPodBase private constructor(
  /** The canonical form: no trailing slash, no query, no fragment. */
  val url: HttpUrl,
) {

  /**
   * The absolute URL of [podRelativePath] under this base.
   *
   * [podRelativePath] is already percent-encoded by the caller — this appends, it does not encode.
   * `HttpUrl.Builder.addPathSegment` is what encodes one segment. A query may be attached with `?`.
   *
   * Refuses a leading slash, a dot segment and a backslash: each is a way to address something
   * other than what the path reads as, and a session's credential travels with whatever it
   * resolves to.
   */
  fun resolve(podRelativePath: String): HttpUrl {
    require(!podRelativePath.startsWith("/")) {
      "A pod-relative path must not start with '/': '$podRelativePath' would address the host root."
    }
    val path = podRelativePath.substringBefore('?')
    require(!path.contains('\\')) { "A pod-relative path must not contain a backslash: '$podRelativePath'" }
    require(path.split('/').none { it == "." || it == ".." }) {
      "A pod-relative path must not contain a dot segment: '$podRelativePath'"
    }
    val separator = if (podRelativePath.isEmpty()) "" else "/"
    return requireNotNull("$url$separator$podRelativePath".toHttpUrlOrNull()) {
      "'$podRelativePath' under '$url' is not a valid URL."
    }
  }

  /**
   * Whether [target] is this pod, or something under it.
   *
   * Asked again when a request is executed, not only when one is built. A `Request` is a plain
   * object holding an absolute URL, so one assembled through session A and executed through session
   * B would otherwise send B's credential to A's pod. Scheme, host and port must match, and the
   * path must be this base or a descendant — a sibling that merely shares the prefix as text
   * (`/alice-archive` under `/alice`) is not under it.
   */
  operator fun contains(target: HttpUrl): Boolean {
    if (target.scheme != url.scheme) return false
    if (!target.host.equals(url.host, ignoreCase = true)) return false
    if (target.port != url.port) return false
    // Segment-wise: `HttpUrl` has already resolved dot segments and rejected a backslash, so this
    // compares what would actually be dialled.
    val base = url.pathSegments.filter { it.isNotEmpty() }
    val reached = target.pathSegments.filter { it.isNotEmpty() }
    return reached.size >= base.size && reached.subList(0, base.size) == base
  }

  override fun equals(other: Any?): Boolean = other is SempodsPodBase && other.url == url

  override fun hashCode(): Int = url.hashCode()

  override fun toString(): String = url.toString()

  companion object {

    /**
     * Binds [baseUrl], or refuses it naming the clause it breaks.
     *
     * @throws IllegalArgumentException when the URL is not a base URL the specification permits.
     */
    @JvmStatic
    fun of(baseUrl: String): SempodsPodBase {
      val reason = reject(baseUrl)
      require(reason == null) { "'$baseUrl' is not a usable pod base URL: $reason" }
      // The trailing slash is trimmed rather than refused: SPS-CORE-019 asks for the canonical form
      // and the two spellings name the same pod, so this accepts the equivalent input.
      val canonical = baseUrl.trimEnd('/')
      return SempodsPodBase(canonical.toHttpUrlOrNull()!!)
    }

    @JvmStatic
    fun of(baseUrl: HttpUrl): SempodsPodBase = of(baseUrl.toString())

    /**
     * Why [baseUrl] is not a pod base URL, or `null` when it is one.
     *
     * Public so a consumer can validate configuration without catching an exception, and so
     * server-side configuration validation can assert against the same function rather than a
     * second copy of the rules.
     */
    @JvmStatic
    fun reject(baseUrl: String): String? {
      // Checked before parsing: `HttpUrl` normalises a dot segment away and would report a path
      // the caller never wrote, so the clause would pass on a URL that breaks it.
      val beforeQuery = baseUrl.substringBefore('?').substringBefore('#')
      val afterAuthority = beforeQuery.substringAfter("//", "").substringAfter('/', "")
      if (afterAuthority.contains('%')) return "path must not contain a percent-encoded octet (SPS-CORE-020)"
      if (afterAuthority.contains('\\')) return "path must not contain a backslash (SPS-CORE-020)"
      if (afterAuthority.split('/').any { it == "." || it == ".." }) {
        return "path must not contain a dot segment (SPS-CORE-020)"
      }

      val url = baseUrl.toHttpUrlOrNull() ?: return "not an absolute http(s) URL"
      if (url.query != null) return "must not carry a query (SPS-CORE-019)"
      if (url.fragment != null) return "must not carry a fragment (SPS-CORE-019)"
      if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        return "must not contain userinfo (SPS-CORE-019)"
      }
      if (url.scheme == "http" && !isLoopback(url.host)) {
        return "http is allowed only on a loopback address (SPS-CORE-019)"
      }
      return null
    }

    /** RFC 6761 reserves `localhost` and `*.localhost` for loopback; literals are checked as such. */
    private fun isLoopback(host: String): Boolean {
      val bare = host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
      if (bare == "localhost" || bare.endsWith(".localhost")) return true
      if (bare == "ip6-localhost" || bare == "ip6-loopback" || bare == "::1") return true
      return bare.startsWith("127.") && bare.split('.').size == 4 &&
        bare.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }
  }
}
