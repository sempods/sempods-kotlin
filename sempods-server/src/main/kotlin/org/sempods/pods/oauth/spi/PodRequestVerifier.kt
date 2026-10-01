package org.sempods.pods.oauth.spi

import org.sempods.spec.PodRef
import java.net.URI

/**
 * Decides who is calling a pod's resource routes: the one boundary between an incoming request and
 * the [PodTokenAuthentication] the rest of the server works with.
 *
 * It takes the request as a [PodResourceRequest] — plain values, no HTTP-framework type — so a
 * verification that needs more than the bearer string (a proof bound to method and target, as DPoP
 * will) can be added behind it without touching an endpoint. Its answer is a verified caller or a
 * classified reason, never a status: the endpoint maps [PodTokenRejection] to 401 or 403.
 *
 * An implementation answers only *is this credential good, and for this pod*. What the verified
 * caller may reach is [org.sempods.pods.grants.PodAuthorizer], and the endpoint asks about a
 * sign-out afterwards. A verifier that accepts every request, or a token of a foreign issuer, breaks
 * this contract.
 *
 * The three outcomes stay distinct: no credentials ([PodTokenAuthentication.NoToken]), rejected
 * credentials ([PodTokenAuthentication.Rejected]) and verified ones
 * ([PodTokenAuthentication.Verified]). A verified caller may still lack authority; that is decided
 * later.
 *
 * The production implementation is `org.sempods.pods.oauth.PodTokenAuthenticator`.
 */
fun interface PodRequestVerifier {

  /** Verifies [request] for [pod]. Must not throw for a request it cannot verify. */
  fun verify(request: PodResourceRequest, pod: PodRef): PodTokenAuthentication
}

/**
 * A request to a pod's resource routes, as far as verifying its credentials needs it.
 *
 * [target] is the request's address as *this deployment* is externally known — built from the
 * configured public base URL, never from a `Host` or `X-Forwarded-*` header the caller or a proxy
 * supplied. [headers] are the request's own, by the name the client sent; use [header] to read one
 * without regard to case.
 */
data class PodResourceRequest(
  val method: String,
  val target: URI,
  val headers: Map<String, List<String>>,
) {

  /** The values of the header called [name], however the client cased it; empty if absent. */
  fun header(name: String): List<String> =
    headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
}
