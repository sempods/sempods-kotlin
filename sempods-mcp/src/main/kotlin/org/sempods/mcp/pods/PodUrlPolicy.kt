package org.sempods.mcp.pods

import org.sempods.client.net.SempodsUrlPolicy
import java.net.InetAddress

/**
 * Pod base-URL guard — the URL-string half of the two-layer SSRF defense (M2 + M6.2).
 *
 * **The rules are [SempodsUrlPolicy]'s**, in `:sempods-client`. They were spelled out twice — here
 * and in a consumer's dereference guard — and the two drifted: this side knew `::a.b.c.d` carries a
 * routable IPv4 a prefix table does not see, that side knew about the 6to4 relay anycast and the
 * discard prefix. Neither knew the other's ranges. One table now, and both consumers get the union.
 *
 * What survives here is the naming this service uses: [allowLocal] for the deploy-time relaxation,
 * and `reject` for pod-base admission.
 *
 * Layer 2 (at connect time): [rejectAddress] vets every DNS-resolved address against the same
 * blocked-range set — enforced by the client's vetting resolver inside the pinned fetch path, so a
 * hostname that (re-)resolves into a blocked range never gets a socket (DNS rebinding closed).
 * Redirects are not followed at all on that path; see `SempodsOutboundGuard`.
 */
class PodUrlPolicy(allowLocal: Boolean) {

  /** The shared rules, exposed so the connect-time hook can take them without a second wrapper. */
  val rules = SempodsUrlPolicy(allowPrivateAddresses = allowLocal)

  /** Returns null if acceptable, otherwise a human-readable rejection reason. */
  fun reject(podBaseUrl: String): String? = rules.rejectPodBase(podBaseUrl)

  /**
   * Vets a DNS-resolved address at connect time (layer 2 — called for every
   * A/AAAA record on every new connection, including re-resolutions). Returns null if
   * acceptable, otherwise a human-readable rejection reason. Always null in relaxed mode —
   * self-host instances point at private pods on purpose.
   */
  fun rejectAddress(addr: InetAddress): String? = rules.rejectAddress(addr)
}
