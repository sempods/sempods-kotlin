package org.sempods.pods.authentication.spi

import org.sempods.pods.grants.PodAuthorizer
import org.sempods.pods.grants.SempodsCredentials

/**
 * The **request-authentication seam** (`docs/concepts/modularity.md`): turns one request to a pod
 * into the [SempodsCredentials] every route below `{pod}/…` works with, or into the reason it
 * cannot.
 *
 * It covers the whole step, not one part of it. The binding `SempodsModule` installs,
 * `org.sempods.pods.authentication.impls.oauth.BearerPodRequestAuthenticator`, looks the pod up, verifies a bearer this
 * server issued, refuses one whose person has signed out since, records a service client's request
 * and asks [PodAuthorizer] which contexts the caller reaches. A deployment whose callers carry
 * another credential binds its own, and with it owns revoking that credential.
 *
 * **What an implementation may decide, and what it may not** is the same as for [PodAuthorizer]:
 * it decides who is calling and which contexts they see, never whether the sandbox applies.
 * [SempodsCredentials.restrictedContexts] is never `null` from here.
 *
 * Takes and returns plain values, so a surface on another web stack can use it as it is. The HTTP
 * status each outcome becomes is the endpoint's: `org.sempods.api.PodApiEndpoint`.
 */
fun interface PodRequestAuthenticator {

  /**
   * Resolves [request] against the pod the route addressed as [pod]. Must not throw for a caller it
   * cannot authenticate; that is [PodRequestAuthentication.Unauthenticated].
   */
  fun authenticate(pod: String, request: PodApiRequest): PodRequestAuthentication
}

/** What [PodRequestAuthenticator.authenticate] found. */
sealed interface PodRequestAuthentication {

  /** The caller, anonymous or not, and what they reach. */
  data class Authenticated(val credentials: SempodsCredentials) : PodRequestAuthentication

  /**
   * No pod by that name, as far as this caller may know. A deployment that hides a pod from some
   * callers answers this rather than [Unauthenticated], so the route looks absent: `404`.
   */
  data object PodNotFound : PodRequestAuthentication

  /**
   * The request carried a credential that does not hold, or carried none where one is required:
   * `401`. [challenge] is the `WWW-Authenticate` value to send with it, or `null` for none.
   */
  data class Unauthenticated(val challenge: String?) : PodRequestAuthentication
}

/**
 * A request as the route received it, relative to where the pod API is mounted.
 *
 * [path] is the encoded path below the API base, with no leading slash — `notes/_system/find` —
 * and [query] the encoded query without its `?`, or `null`. Both stay as the client sent them, so a
 * credential bound to the target sees the same bytes it signed. Where the API base is publicly
 * known is the authenticator's to add: the default binding takes it from configuration, never from
 * a `Host` or `X-Forwarded-*` header.
 */
data class PodApiRequest(
  val method: String,
  val path: String,
  val query: String?,
  val headers: Map<String, List<String>>,
)
