package org.sempods.client

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.sempods.client.net.SempodsUrlPolicy
import java.util.Locale

/**
 * One pod's base URL and the address it is reached at, each validated once and then trusted.
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
 * **The spelling bound is the one a URL parser writes.** `https://Pods.Example:443/alice` binds
 * `https://pods.example/alice`: `HttpUrl` lowercases the host and drops the default port. No clause
 * forbids the first spelling, so it is accepted. A pod's IRIs are compared against [url] as
 * strings, though, because an IRI is its string
 * ([RDF 1.1 Concepts §3.2](https://www.w3.org/TR/rdf11-concepts/#section-IRIs)). Requests to a pod
 * that mints `https://pods.example:443/alice/events/1` pass [contains], but `resources()` and
 * `contexts()` refuse every IRI it hands out.
 *
 * **A pod has a name and an address, usually the same URL.** [url] is the name: the pod's IRIs, the
 * issuer of its tokens and the pages a browser opens hang from it. [address] is where this process
 * sends the pod's requests, and where a session's credential goes. Behind a proxy that terminates
 * TLS, an in-cluster caller names the pod on its public host and reaches it at the internal one:
 *
 * ```kotlin
 * SempodsPodBase.of("https://acme.example/api/pod")
 *   .reachedOverPlaintextAt("http://sempods.internal:8080/api/pod")
 * ```
 *
 * The pod server checks `SEMPODS_PUBLIC_BASE_URL` with [reject] too, and refuses a value that [of]
 * would bind under another spelling.
 */
class SempodsPodBase private constructor(
  /** The pod's name, in canonical form: no trailing slash, no query, no fragment. */
  val url: HttpUrl,
  /**
   * Where this process sends the pod's requests, canonical like [url]. It is [url] unless
   * [reachedAt] or [reachedOverPlaintextAt] named another.
   */
  val address: HttpUrl,
) {

  private val segments: List<String> = segmentsOf(url)

  private val addressSegments: List<String> = if (address == url) segments else segmentsOf(address)

  /**
   * The absolute URL of [podRelativePath] under [url] — the page a browser opens. A request's path
   * resolves under [address] instead, by the same rules ([SempodsSession.newRequest]).
   *
   * [podRelativePath] is already percent-encoded by the caller — this appends, it does not encode.
   * `HttpUrl.Builder.addPathSegment` is what encodes one segment. A query may be attached with `?`;
   * a query alone, `?view=summary`, addresses the base itself.
   *
   * Refuses a leading slash, a dot segment, a backslash and an encoded `/` or `\`: each is a way to
   * address something other than what the path reads as, and a session's credential travels with
   * whatever it resolves to.
   */
  fun resolve(podRelativePath: String): HttpUrl = under(url, podRelativePath)

  /** [resolve], under [address]: the URL a request for [podRelativePath] goes to. */
  @JvmSynthetic
  internal fun dial(podRelativePath: String): HttpUrl = under(address, podRelativePath)

  /**
   * Whether [target] is this pod, or something under it, by its name [url].
   *
   * Scheme, host and port must match, and the path must be this base or a descendant — a sibling
   * that merely shares the prefix as text (`/alice-archive` under `/alice`) is not under it.
   */
  operator fun contains(target: HttpUrl): Boolean = holds(url, segments, target)

  /**
   * [contains], asked of [address]: whether a request for [target] stays with this pod.
   *
   * Asked again when a request is executed, not only when one is built. A `Request` is a plain
   * object holding an absolute URL, so one assembled through session A and executed through session
   * B would otherwise send B's credential to A's pod.
   */
  @JvmSynthetic
  internal fun reaches(target: HttpUrl): Boolean = holds(address, addressSegments, target)

  /**
   * This pod, reached at [address]. [url] stays its name.
   *
   * [address] must be a URL [of] would accept, `https` included. Its path may differ from [url]'s:
   * a pod-relative path resolves under each the same way. An answer's [SempodsResponse.url] is the
   * URL at [address] the request went to.
   *
   * @throws IllegalArgumentException when [address] is not a usable address.
   */
  fun reachedAt(address: String): SempodsPodBase = SempodsPodBase(url, bindAddress(address, plaintext = false))

  /**
   * [reachedAt], with `http` allowed on any host: for a hop the deployment trusts, such as the one
   * behind a proxy that terminates TLS. The session's credential crosses it in plain text.
   *
   * [url] is still held to SPS-CORE-019, so a pod's name stays `https` off loopback.
   *
   * @throws IllegalArgumentException when [address] is not a usable address.
   */
  fun reachedOverPlaintextAt(address: String): SempodsPodBase = SempodsPodBase(url, bindAddress(address, plaintext = true))

  override fun equals(other: Any?): Boolean = other is SempodsPodBase && other.url == url && other.address == address

  override fun hashCode(): Int = 31 * url.hashCode() + address.hashCode()

  /** The name, [url]. */
  override fun toString(): String = url.toString()

  private fun bindAddress(address: String, plaintext: Boolean): HttpUrl {
    val (bound, reason) = validate(address, plaintext)
    if (bound != null) return bound
    val hint = if (!plaintext && validate(address, plaintext = true).first != null) "; reachedOverPlaintextAt accepts it" else ""
    throw IllegalArgumentException("'$address' is not a usable address for the pod '$url': $reason$hint")
  }

  private fun under(base: HttpUrl, podRelativePath: String): HttpUrl {
    require(!podRelativePath.startsWith("/")) {
      "A pod-relative path must not start with '/': '$podRelativePath' would address the host root."
    }
    val path = podRelativePath.substringBefore('?').substringBefore('#')
    require(!path.contains('\\')) { "A pod-relative path must not contain a backslash: '$podRelativePath'" }
    // `%2F` and `%5C` are separators to a server that decodes before it routes.
    require(listOf("%2f", "%5c").none { path.contains(it, ignoreCase = true) }) {
      "A pod-relative path must not contain an encoded '/' or '\\': '$podRelativePath'"
    }
    // `%2e` is a dot to a URL parser, so `%2e%2e` leaves the pod as surely as `..` does.
    require(path.split('/').map { it.replace("%2e", ".", ignoreCase = true) }.none { it == "." || it == ".." }) {
      "A pod-relative path must not contain a dot segment: '$podRelativePath'"
    }
    val separator = if (path.isEmpty()) "" else "/"
    // A pod at the host root renders as `https://pods.example/`, whose slash the separator repeats.
    return requireNotNull("${base.toString().removeSuffix("/")}$separator$podRelativePath".toHttpUrlOrNull()) {
      "'$podRelativePath' under '$base' is not a valid URL."
    }
  }

  private fun holds(base: HttpUrl, baseSegments: List<String>, target: HttpUrl): Boolean {
    if (target.scheme != base.scheme) return false
    if (!target.host.equals(base.host, ignoreCase = true)) return false
    if (target.port != base.port) return false
    // Segment-wise and with empty segments kept: `HttpUrl` has already resolved dot segments, so this
    // compares what would actually be dialled, and `//alice` is not `/alice`. The segments are decoded,
    // and one holding a `/` or `\` is several to a server that decodes before it routes.
    val reached = target.pathSegments
    return reached.size >= baseSegments.size && reached.subList(0, baseSegments.size) == baseSegments &&
      reached.none { '/' in it || '\\' in it }
  }

  companion object {

    /**
     * Binds [baseUrl], or refuses it naming the clause it breaks.
     *
     * @throws IllegalArgumentException when the URL is not a base URL the specification permits.
     */
    @JvmStatic
    fun of(baseUrl: String): SempodsPodBase {
      val (bound, reason) = validate(baseUrl, plaintext = false)
      requireNotNull(bound) { "'$baseUrl' is not a usable pod base URL: $reason" }
      return SempodsPodBase(bound, bound)
    }

    /**
     * Why [baseUrl] is not a pod base URL, or `null` when it is one.
     *
     * Public so a consumer can validate configuration without catching an exception, and so
     * server-side configuration validation can assert against the same function rather than a
     * second copy of the rules.
     */
    @JvmStatic
    fun reject(baseUrl: String): String? = validate(baseUrl, plaintext = false).second

    /**
     * [spelled] bound in canonical form, or why it is not a base URL. [plaintext] lifts the one
     * clause an address may break: `http` off a loopback address.
     */
    private fun validate(spelled: String, plaintext: Boolean): Pair<HttpUrl?, String?> {
      // Checked before parsing: `HttpUrl` normalises a dot segment away and would report a path
      // the caller never wrote, so the clause would pass on a URL that breaks it.
      val beforeQuery = spelled.substringBefore('?').substringBefore('#')
      val afterAuthority = beforeQuery.substringAfter("//", "").substringAfter('/', "")
      if (afterAuthority.contains('%')) return refused("path must not contain a percent-encoded octet (SPS-CORE-020)")
      if (afterAuthority.contains('\\')) return refused("path must not contain a backslash (SPS-CORE-020)")
      if (afterAuthority.split('/').any { it == "." || it == ".." }) {
        return refused("path must not contain a dot segment (SPS-CORE-020)")
      }
      // Empty segments are part of a path here, so `/alice//` is not `/alice` with a spelling variant.
      if ("/$afterAuthority".endsWith("//")) return refused("path must not end in more than one slash (SPS-CORE-019)")

      // One trailing slash is trimmed rather than refused: SPS-CORE-019 asks for the canonical form
      // and the two spellings name the same pod, so this accepts the equivalent input.
      val url = spelled.removeSuffix("/").toHttpUrlOrNull() ?: return refused("not an absolute http(s) URL")
      if (url.query != null) return refused("must not carry a query (SPS-CORE-019)")
      if (url.fragment != null) return refused("must not carry a fragment (SPS-CORE-019)")
      if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        return refused("must not contain userinfo (SPS-CORE-019)")
      }
      if (!plaintext && url.scheme == "http" && !isLoopback(url.host)) {
        return refused("http is allowed only on a loopback address (SPS-CORE-019)")
      }
      return url to null
    }

    private fun refused(reason: String): Pair<HttpUrl?, String?> = null to reason

    private fun segmentsOf(url: HttpUrl): List<String> = url.pathSegments.dropLastWhile { it.isEmpty() }

    /** A loopback name ([SempodsUrlPolicy.isLoopbackName]) or a loopback literal. */
    private fun isLoopback(host: String): Boolean {
      val bare = host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
      if (SempodsUrlPolicy.isLoopbackName(bare) || bare == "::1") return true
      val octets = bare.split('.')
      return octets.size == 4 && octets[0] == "127" &&
        octets.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }
  }
}
