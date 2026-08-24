package org.sempods.client.net

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Whether a URL may be dialled at all — the guard in front of every outbound request the client
 * makes, because a caller that takes a URL from somewhere else is a caller that can be pointed at
 * `169.254.169.254`.
 *
 * **Three entry points, one rule set**, because the same address question is asked at three
 * different moments and the answers must not drift apart:
 *
 * - [rejectPodBase] — admission: is this a URL worth *holding a credential for*? The strictest of
 *   the three, and the only one that insists on `https` and refuses userinfo and fragments. A pod
 *   base is a coordinate a service stores, not an arbitrary document address.
 * - [rejectTarget] — per request, immediately before the connection. Deliberately weaker: it must
 *   pass an ordinary Linked-Data URI, which is routinely `http` and routinely carries a fragment.
 *   What it does not pass is a host that is a non-global address in any spelling.
 * - [rejectAddress] — at connect time, for every address a name resolves to. This is the one that
 *   closes DNS rebinding, and it only works from inside the connection path; the resolver adapter
 *   that calls it is an implementation detail of [SempodsOutboundGuard].
 *
 * **Both address layers are needed, and neither is redundant.** [rejectAddress] alone misses
 * IP-literal hosts entirely — an HTTP engine resolving `http://169.254.169.254/` has nothing to
 * ask a resolver about and connects straight through. [rejectTarget] alone is the check-then-connect
 * race this class exists to close. Together: literals are caught before the call, names are caught
 * as they resolve.
 *
 * [allowPrivateAddresses] is deploy-time policy, and a constructor parameter rather than a lookup:
 * this is a library, and reading an ambient environment here would decide for a consumer that has
 * its own answer. Local development points at a pod on loopback on purpose; production never does.
 */
class SempodsUrlPolicy(private val allowPrivateAddresses: Boolean) {

  /**
   * Admission for a pod base URL — the strict entry point. Returns `null` when acceptable,
   * otherwise a human-readable reason.
   *
   * `https` is required because a pod base is dialled with a bearer; `http` is allowed only on a
   * loopback host and only in the relaxed mode, which is what a self-hosted instance runs.
   */
  fun rejectPodBase(podBaseUrl: String): String? {
    val uri = runCatching { URI(podBaseUrl) }.getOrNull() ?: return "not a valid URL"
    if (!uri.isAbsolute) return "must be an absolute URL"
    if (uri.userInfo != null) return "must not contain userinfo"
    if (uri.fragment != null) return "must not contain a fragment"
    val scheme = uri.scheme?.lowercase() ?: return "missing scheme"
    val host = uri.host?.lowercase() ?: return "missing host"

    when (scheme) {
      "https" -> {}
      "http" -> if (!(allowPrivateAddresses && isLoopbackHost(host))) {
        return "http is only allowed on loopback in local mode"
      }
      else -> return "scheme must be https"
    }

    return rejectHost(host)
  }

  /**
   * The per-request guard. Returns `null` when acceptable, otherwise a typed [Refusal].
   *
   * Scheme and host only — no opinion on fragments, userinfo or `https`, because this also has to
   * pass a foreign Linked-Data URI that the caller did not mint. Whether such a URI may be
   * dereferenced at all is the caller's question; whether its host is somewhere this process must
   * not connect is this one.
   */
  fun rejectTarget(uri: URI): Refusal? {
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return Refusal(Refusal.Kind.SCHEME, "not an HTTP(S) URI")
    val host = uri.host?.lowercase() ?: return Refusal(Refusal.Kind.NO_HOST, "URI has no host")
    return rejectHost(host)?.let { Refusal(Refusal.Kind.NON_GLOBAL_HOST, it) }
  }

  /**
   * Why a target was refused. Typed rather than a bare string because the caller phrases the two
   * apart: a scheme it will never dereference is a different answer to give than a host it must
   * not connect to, and matching on message text to tell them apart is how that breaks silently.
   */
  data class Refusal(val kind: Kind, val detail: String) {
    enum class Kind { SCHEME, NO_HOST, NON_GLOBAL_HOST }
  }

  /**
   * Vets one resolved address at connect time — called for every A/AAAA record on every new
   * connection, re-resolutions included. Returns `null` when acceptable, otherwise a reason.
   */
  fun rejectAddress(address: InetAddress): String? =
    if (allowPrivateAddresses) null else blockedRangeReason(address)

  /** Loopback *names* and IP literals in any spelling; DNS names are left to [rejectAddress]. */
  private fun rejectHost(host: String): String? {
    if (allowPrivateAddresses) return null
    // Loopback names, not just literals: `https://localhost:8443/…` would otherwise be dialled.
    if (isLoopbackName(host)) return "host is a loopback name"
    // Any IP-literal spelling java.net accepts — dotted-quad, decimal dword (2130706433),
    // short form (127.1), hex (0x7f000001), IPv6. A numeric host we cannot canonicalise is
    // refused outright rather than left to slip through as a name.
    if (!isNumericHost(host)) return null
    val address = parseIpLiteral(host) ?: return "host is a non-canonical numeric address"
    return blockedRangeReason(address)?.let { "host is a non-global address ($it)" }
  }

  private fun isLoopbackHost(host: String) =
    isLoopbackName(host) || parseIpLiteral(host)?.isLoopbackAddress == true

  /** RFC 6761 reserves `localhost` and `*.localhost` for loopback. */
  private fun isLoopbackName(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") ||
      host == "ip6-localhost" || host == "ip6-loopback"

  private fun isNumericHost(host: String): Boolean {
    val bare = host.trim('[', ']')
    return if (bare.contains(':')) true else bare.matches(NUMERIC_IPV4)
  }

  private fun parseIpLiteral(host: String): InetAddress? {
    if (!isNumericHost(host)) return null
    return runCatching { InetAddress.getByName(host.trim('[', ']')) }.getOrNull()
  }

  companion object {

    /**
     * IANA's IPv4 Special-Purpose Address Registry — every prefix that is not global unicast.
     *
     * Written out rather than assembled from the [InetAddress] predicates because those do not
     * answer the question. `isSiteLocalAddress` tests the **deprecated** `fec0::/10` for IPv6, so
     * unique local addresses (`fc00::/7`) — what a container network actually uses — report
     * `false`; and nothing in the JDK knows about carrier NAT or the transition ranges below.
     * A table is also reviewable: one place to compare against the registry.
     */
    private val NON_GLOBAL_IPV4 = listOf(
      Cidr("0.0.0.0/8", "this host on this network"),
      Cidr("10.0.0.0/8", "private"),
      Cidr("100.64.0.0/10", "carrier-grade NAT"),
      Cidr("127.0.0.0/8", "loopback"),
      Cidr("169.254.0.0/16", "link-local — cloud metadata lives here"),
      Cidr("172.16.0.0/12", "private"),
      Cidr("192.0.0.0/24", "IETF protocol assignments"),
      Cidr("192.0.2.0/24", "TEST-NET-1"),
      Cidr("192.88.99.0/24", "6to4 relay anycast (deprecated)"),
      Cidr("192.168.0.0/16", "private"),
      Cidr("198.18.0.0/15", "benchmarking"),
      Cidr("198.51.100.0/24", "TEST-NET-2"),
      Cidr("203.0.113.0/24", "TEST-NET-3"),
      Cidr("224.0.0.0/4", "multicast"),
      Cidr("240.0.0.0/4", "reserved, incl. the 255.255.255.255 broadcast"),
    )

    /**
     * The same for IPv6.
     *
     * The transition ranges are the ones worth spelling out: `2002::/16` (6to4), `2001::/32`
     * (Teredo) and `64:ff9b::/96` (NAT64) each **embed an IPv4 address**, so on a host where the
     * mechanism is configured, `2002:0a00:0001::` reaches `10.0.0.1`. They look globally routable
     * and are the obvious way around a guard that only knows about private ranges.
     */
    private val NON_GLOBAL_IPV6 = listOf(
      Cidr("::/128", "unspecified"),
      Cidr("::1/128", "loopback"),
      Cidr("64:ff9b::/96", "NAT64 — embeds IPv4"),
      Cidr("64:ff9b:1::/48", "local-use NAT64 — embeds IPv4"),
      Cidr("100::/64", "discard-only"),
      Cidr("2001::/32", "Teredo — embeds IPv4"),
      Cidr("2001:db8::/32", "documentation"),
      Cidr("2002::/16", "6to4 — embeds IPv4"),
      Cidr("fc00::/7", "unique local addresses"),
      Cidr("fe80::/10", "link-local"),
      Cidr("fec0::/10", "site-local (deprecated, still routed by some stacks)"),
      Cidr("ff00::/8", "multicast"),
    )

    /**
     * Whether [address] is global unicast — an address the open internet can route to, and the only
     * kind a pod anyone can read will have.
     *
     * IPv4-mapped IPv6 literals (`::ffff:127.0.0.1`) need no entry of their own: the JDK hands
     * those back as an [java.net.Inet4Address], so the IPv4 table sees them.
     *
     * Public so the rule can be asserted on both sides without a network: a test that had to
     * connect somewhere to prove a public address passes would be slow, flaky, and would stop
     * testing this function.
     */
    fun isPubliclyRoutable(address: InetAddress): Boolean = blockedRangeReason(address) == null

    /** The one blocked-range definition behind all three entry points. */
    private fun blockedRangeReason(address: InetAddress): String? {
      val bytes = address.address
      val table = if (address is Inet6Address) NON_GLOBAL_IPV6 else NON_GLOBAL_IPV4
      table.firstOrNull { it.contains(bytes) }?.let { return "${it.notation} — ${it.reason}" }
      // The tables cover the prefixes that are wholly non-global. What they cannot cover is a
      // form whose *payload* decides: `::a.b.c.d` (v4-compatible) is only as routable as the
      // IPv4 address it carries, and the address it carries may be 10.0.0.1.
      if (address is Inet6Address) {
        embeddedIpv4(bytes)?.let { v4 ->
          blockedRangeReason(InetAddress.getByAddress(v4))
            ?.let { return "embedded IPv4 in a non-global range ($it)" }
        }
      }
      return null
    }

    /**
     * The trailing IPv4 of a v4-mapped (`::ffff:0:0/96`) or v4-compatible (`::/96`) IPv6 address.
     * The NAT64 prefixes need no extraction — the tables above refuse them outright.
     */
    private fun embeddedIpv4(b: ByteArray): ByteArray? {
      if (b.size != 16) return null
      val leadingZeroes = (0..9).all { b[it].toInt() == 0 }
      if (!leadingZeroes) return null
      val v4Mapped = (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
      val v4Compatible = b[10].toInt() == 0 && b[11].toInt() == 0
      return if (v4Mapped || v4Compatible) b.copyOfRange(12, 16) else null
    }

    /** 1–4 numeric labels, each decimal or `0x`-hex — the IPv4 literal forms java.net accepts. */
    private val NUMERIC_IPV4 = Regex("""(0x[0-9a-fA-F]+|[0-9]+)(\.(0x[0-9a-fA-F]+|[0-9]+)){0,3}""")

    /** A CIDR prefix, matched against raw address bytes. */
    private class Cidr(val notation: String, val reason: String) {

      private val prefix: ByteArray
      private val prefixLength: Int

      init {
        val (address, length) = notation.split("/")
        prefix = InetAddress.getByName(address).address
        prefixLength = length.toInt()
      }

      fun contains(address: ByteArray): Boolean {
        if (address.size != prefix.size) return false
        val wholeBytes = prefixLength / 8
        for (i in 0 until wholeBytes) {
          if (address[i] != prefix[i]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (address[wholeBytes].toInt() and mask) == (prefix[wholeBytes].toInt() and mask)
      }
    }
  }
}
