package org.sempods.client.net

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * A hostname resolved into a blocked range. Subclasses [UnknownHostException] so the HTTP engine
 * treats it as a resolution failure (fail the call, no socket) rather than an unexpected crash —
 * and so it stays recognisable in a cause chain, which is how a caller tells a refused *address*
 * apart from a refused *credential*.
 */
class SsrfBlockedException(message: String) : UnknownHostException(message)

/** A request refused by [OutboundRateLimiter] before it left the process. */
class SempodsRateLimitedException(message: String) : RuntimeException(message)

/**
 * A budget on outbound requests, asked once per request before the connection.
 *
 * A seam rather than an implementation: how a budget is keyed is the caller's knowledge — one
 * consumer keys per pod base, another might key per tenant — and the client has no business
 * deciding it. Returning `false` refuses the request with [SempodsRateLimitedException].
 */
fun interface OutboundRateLimiter {
  fun tryAcquire(target: URI): Boolean
}

/**
 * The connect-time half of the SSRF defense (resolve-and-pin): an OkHttp [Dns] that resolves via
 * the system resolver and vets **every** returned address through [SempodsUrlPolicy.rejectAddress]
 * before a socket is opened.
 *
 * OkHttp connects to exactly the addresses this hook returns — resolution and connection are the
 * same event, so there is no resolve→connect window (DNS rebinding / TOCTOU closed). Every new
 * connection re-enters this hook, including re-resolutions after pool eviction. Pooled connections
 * are inherently pinned: they were vetted when the socket was opened.
 *
 * **This hook is half the defense and cannot be the whole of it.** An engine handed an IP-literal
 * host has nothing to resolve and never asks a [Dns] at all, so `http://169.254.169.254/` would
 * walk straight past it. [SempodsUrlPolicy.rejectTarget], applied per request before the call, is
 * the other half.
 *
 * If **any** resolved address is blocked, the whole lookup is rejected instead of filtering to the
 * public subset — an attacker's DNS could otherwise serve mixed records and play failover roulette.
 * In relaxed mode ([SempodsUrlPolicy.rejectAddress] returns null) this is a pass-through.
 *
 * [trustedHosts] (exact hostnames) skip the range check — see [SempodsOutboundGuard] for why the
 * decision lives there rather than here.
 *
 * [resolve] is injectable for tests only; production uses the system resolver.
 *
 * **`internal` on purpose.** It implements the engine's resolver interface, and a public class that
 * does so would put the engine on a consumer's compile classpath — which `implementation` says it
 * is not on — and make an engine major version part of this library's ABI. What a caller needs from
 * here is [SempodsOutboundGuard.SYSTEM_RESOLVE], which is a plain function type.
 */
internal class VettingDns(
  private val policy: SempodsUrlPolicy,
  private val trustedHosts: Set<String> = emptySet(),
  private val resolve: (String) -> List<InetAddress> = SempodsOutboundGuard.SYSTEM_RESOLVE,
) : Dns {

  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = resolve(hostname)
    if (addresses.isEmpty()) throw UnknownHostException("no addresses for '$hostname'")
    if (hostname.lowercase().removeSurrounding("[", "]") in trustedHosts) return addresses
    for (address in addresses) {
      policy.rejectAddress(address)?.let { reason ->
        throw SsrfBlockedException(
          "host '$hostname' resolves to a blocked address ($reason): ${address.hostAddress}"
        )
      }
    }
    return addresses
  }

}

/**
 * Everything a transport needs to refuse a request it must not make. Opt-in: a transport without
 * one behaves as it always did, which is why adding this took nothing away from consumers that dial
 * only pods they configured themselves.
 *
 * Two address layers plus a budget, and the pairing is the point — see [VettingDns] for why the DNS
 * hook alone leaves IP literals uncovered, and [SempodsUrlPolicy] for why the per-request check
 * alone is the check-then-connect race.
 *
 * **[proxyless] is not a detail.** With a proxy configured, the *proxy* resolves the hostname and
 * the DNS hook is never consulted — the whole defense is bypassed by a JVM system property. A
 * guarded transport therefore pins "no proxy" unless a deployment knowingly says otherwise.
 */
class SempodsOutboundGuard(
  val policy: SempodsUrlPolicy,
  /**
   * Exact hostnames that skip the address checks.
   *
   * **SCOPE WITH CARE.** The exemption belongs only on a transport whose *every* request targets
   * deploy-time-trusted configuration — an identity provider's discovery, JWKS and token endpoints.
   * On a transport that dials user-supplied URLs, a URL naming the trusted host would ride the
   * exemption into an internal network.
   *
   * Matched case-insensitively and with the brackets of an IPv6 literal stripped, because the two
   * places a host arrives from spell it differently: `URI.getHost()` keeps them (`[fd00::1]`), the
   * engine's resolver hook does not (`fd00::1`). Normalising here is what stops an IPv6 issuer's
   * exemption from silently never matching.
   */
  val trustedHosts: Set<String> = emptySet(),
  val rateLimiter: OutboundRateLimiter? = null,
  val proxyless: Boolean = true,
  /** Test seam only; production resolves through the system. */
  val resolve: (String) -> List<InetAddress> = SYSTEM_RESOLVE,
) {

  private val trusted: Set<String> = trustedHosts.map(::normalizeHost).toSet()

  /**
   * Whether this target may be dialled at all — the per-request half of the defense, and the half
   * that must consult [trustedHosts] as well.
   *
   * **Both halves ask one object on purpose.** Wiring the exemption into the resolver alone leaves
   * it useless for exactly the hosts that need it: an IP literal or a loopback name never reaches a
   * resolver, so it would be refused here before the exemption could ever apply — a private-network
   * issuer would be unreachable while looking configured.
   *
   * The exemption covers the **host**, not the scheme. A trusted host does not make `file:`
   * dialable.
   */
  internal fun refuseTarget(uri: URI): SempodsUrlPolicy.Refusal? {
    val refusal = policy.rejectTarget(uri) ?: return null
    val exempt = refusal.kind == SempodsUrlPolicy.Refusal.Kind.NON_GLOBAL_HOST && isTrusted(uri.host)
    return if (exempt) null else refusal
  }

  /** `false` refuses the request. A trusted host is not charged against a budget either. */
  internal fun allows(target: URI): Boolean =
    rateLimiter == null || isTrusted(target.host) || rateLimiter.tryAcquire(target)

  internal fun dns(): Dns = VettingDns(policy, trusted, resolve)

  private fun isTrusted(host: String?): Boolean = host != null && normalizeHost(host) in trusted

  private fun normalizeHost(host: String): String = host.lowercase().removeSurrounding("[", "]")

  companion object {
    /**
     * The production resolver, as a plain function.
     *
     * A function type rather than the engine's resolver interface, because this is the one piece of
     * the connect-time check a caller ever names — the adapter that implements it is `internal`, so
     * a consumer of the published artifact never needs the engine on its compile classpath and an
     * engine major version is not part of this library's ABI.
     */
    val SYSTEM_RESOLVE: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
  }
}
