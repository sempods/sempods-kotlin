package org.sempods.api.pod.system.find

import org.sempods.pods.grants.SempodsCredentials
import org.sempods.retrieval.FindSandbox
import java.net.URI

/**
 * Authenticates a `find` request for [FindHttpHandler]. The handler calls it once, after the
 * request parsed and the `Accept` header negotiated, so a malformed request answers `400` and an
 * unacceptable one `406` before any credential is looked at.
 *
 * It may throw a `WebApplicationException` — a `401` for a rejected bearer, a `403`, a `404` for an
 * unknown pod — and the handler lets it propagate untouched.
 */
fun interface FindAuthorizer {
  fun authorize(): FindAuthorization
}

/**
 * Resolves a `find` context downscope for an authorized caller.
 *
 * [resolve] receives the requested context IRIs as the caller wrote them, blank values already
 * dropped and never an empty list. It returns `requested ∩ readable`, dropping unknown, malformed
 * and unreadable contexts **silently** (`SPS-CRUD-015`) — an error would tell the caller which
 * contexts exist. An empty set is a valid answer and yields an empty result.
 */
fun interface FindContextResolver {
  fun resolve(requested: List<String>): Set<URI>
}

/**
 * What a [FindAuthorizer] grants a `find` request: the [FindSandbox] base, the downscope resolver
 * and the cache policy.
 *
 * @property podUri the pod's URI — [FindSandbox.podUri].
 * @property credentials the authorized caller — [FindSandbox.credentials].
 * @property visibleContexts the caller's readable contexts, the ceiling no hit may leave —
 *   [FindSandbox.visibleContexts].
 * @property contextResolver called only when the request names a non-blank context.
 * @property cacheControl the `Cache-Control` value of the successful response. It has to keep a
 *   shared cache from serving one caller's result to another: a result read with a credential is
 *   `private, no-store`.
 */
class FindAuthorization(
  val podUri: URI,
  val credentials: SempodsCredentials,
  val visibleContexts: Set<URI>?,
  val contextResolver: FindContextResolver,
  val cacheControl: String,
)
