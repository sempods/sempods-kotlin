package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.sempods.SempodsModule
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.PodContextPermissionResolver
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI

/**
 * Pre-write validation shared by [PodResourceWriteService] (LOD-layer writes) and
 * [org.sempods.api.pod.system.resources.PodSlotWriteService] (System-layer slot writes).
 *
 * Both layers funnel through the same two gates:
 *
 * 1. [resolveWriteContextOrThrow] — parse the raw `?context=` parameter, normalize it against
 *    the pod base URL, reject out-of-namespace / fragmented URIs, and confirm the context is
 *    registered.
 * 2. [authorizeWriteOrThrow] — match the resolved context against the caller's OAuth scopes,
 *    honoring `<root>#manage` only along slash-delimited descendants so a `tasks#manage` grant
 *    cannot reach the sibling `tasks-private` context.
 *
 * Extracted from `PodResourceWriteService` so the two write paths cannot drift.
 */
class PodContextWriteAuthorizer @Inject constructor(
  private val podContextsDao: PodContextsDao,
  private val podContextPermissionResolver: PodContextPermissionResolver,
  private val podFacade: PodFacade,
) {

  fun resolveWriteContextOrThrow(pod: String, rawContext: String?): URI {
    val raw = rawContext?.trim()?.takeIf(String::isNotEmpty)
      ?: throw WebApplicationException(
        Response.status(400).entity("missing context").type(MediaType.TEXT_PLAIN).build()
      )

    val podBaseUrl = "${SempodsModule.config.apiBaseUrl}${pod}/"
    val contextUri = if (raw.contains("://")) {
      try {
        URI(raw)
      } catch (_: Exception) {
        throw WebApplicationException(
          Response.status(400).entity("invalid context URI").type(MediaType.TEXT_PLAIN).build()
        )
      }
    } else {
      URI("${podBaseUrl}${raw.trimStart('/')}")
    }

    if (!contextUri.toString().startsWith(podBaseUrl)) {
      throw WebApplicationException(
        Response.status(400).entity("context must be inside pod namespace").type(MediaType.TEXT_PLAIN).build()
      )
    }
    if (contextUri.fragment != null) {
      throw WebApplicationException(
        Response.status(400).entity("context URI must not contain fragment").type(MediaType.TEXT_PLAIN).build()
      )
    }
    if (!contextIsRegistered(pod, contextUri)) {
      throw WebApplicationException(
        Response.status(404).entity("unknown context").type(MediaType.TEXT_PLAIN).build()
      )
    }
    return contextUri
  }

  /**
   * Resolve a single write-side `?context=` parameter from a query-list value. Writes target
   * exactly one context per `SPS-CRUD-007` (sempods-spec); repeated `?context=` is rejected with
   * `400` regardless of whether any of the repeats are blank — `?context=valid&context=` is
   * still a repeated parameter, and the strict "exactly one" rule applies to the parameter
   * occurrence count, not to the count of non-blank values.
   *
   * Missing `?context=` (no parameter at all, or a single blank value) is also `400`,
   * delegated to [resolveWriteContextOrThrow].
   */
  fun resolveSingleWriteContextOrThrow(pod: String, rawContexts: List<String>?): URI {
    val raw = rawContexts ?: emptyList()
    if (raw.size > 1) {
      throw WebApplicationException(
        Response.status(400)
          .entity("write operations require exactly one ?context= parameter")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
    return resolveWriteContextOrThrow(pod, raw.firstOrNull())
  }

  /**
   * Resolve a read-side `?context=` downscope filter into a set of context URIs, intersected
   * with the pod's known contexts. Empty input → `null` (caller-side filtering by readable
   * contexts takes over). The parameter itself is `SPS-CRUD-014`; that unknown and unreadable
   * contexts are excluded **silently** is `SPS-CRUD-015`, and the reason is `SPS-CORE-017` — an
   * error here would answer "this context exists" to anyone who guessed one.
   *
   * NOTE: This does NOT intersect with the caller's readable contexts; callers must do that
   * step themselves so they can keep their existing visibility filter (anonymous public
   * contexts, `public-read` scope, etc.). What this helper guarantees is parsing &
   * normalization against the pod's context registry.
   */
  fun resolveReadDownscopeOrEmpty(pod: String, rawContexts: List<String>?): Collection<URI>? {
    val raw = rawContexts?.filter { it.isNotBlank() } ?: emptyList()
    if (raw.isEmpty()) return null
    return raw.mapNotNull { value ->
      try {
        resolveWriteContextOrThrow(pod, value)
      } catch (e: WebApplicationException) {
        val status = e.response?.status ?: 0
        // For reads, unknown / invalid contexts are silent — drop and continue.
        if (status == 400 || status == 404) null else throw e
      }
    }.toSet()
  }

  /**
   * Re-assert that [contextUri] is *still* registered, immediately before a write commits.
   *
   * [resolveWriteContextOrThrow] already checks this — but it checks it when the request is
   * *parsed*, and a write that takes real time in between can commit into a context that was
   * deleted meanwhile. The media upload is where that stops being theoretical: it buffers the body
   * first, and on the copy-from-URL path it fetches a remote source under a request timeout
   * measured in **tens of seconds**. `PodFacade.removeContext` runs its cascade inside that window
   * quite plausibly, and the upload would then re-create the assignment it had just pulled — a
   * media attached to a context that no longer exists, never unreferenced and therefore never
   * collectable, and readable for as long as somebody holds an unexpired token naming that context.
   *
   * So this is the same live check, moved to where the decision is actually taken. It narrows the
   * window from the duration of the whole request to the gap between this call and the write, and
   * it does **not** close it: the context registry and the media registry are separate documents
   * with no shared transaction. That residual is recorded in `docs/media.md`
   * §"Named limitations" rather than papered over.
   *
   * `409` rather than `404`: the caller's request was well-formed and authorized when it arrived,
   * and the state changed underneath it. That is what makes a retry the sensible next move and a
   * bug report the wrong one.
   */
  fun requireContextStillRegisteredOrThrow(pod: String, contextUri: URI) {
    if (!contextIsRegistered(pod, contextUri)) {
      throw WebApplicationException(
        Response.status(409)
          .entity("context '$contextUri' was deleted while this request was in flight")
          .type(MediaType.TEXT_PLAIN)
          .build()
      )
    }
  }

  fun authorizeWriteOrThrow(credentials: SempodsCredentials, contextUri: URI) {
    val writeScope = "${contextUri}#write"
    if (credentials.oauthScopes.contains(writeScope)) return
    if (isCoveredByManageScope(credentials, contextUri)) return

    throw WebApplicationException(
      Response.status(403)
        .entity("missing write permission for context '$contextUri'")
        .type(MediaType.TEXT_PLAIN)
        .build()
    )
  }

  /**
   * Slash-delimited manage-root coverage: `true` when one of [credentials]'s `<root>#manage`
   * scopes covers [contextUri] — the root itself or a `<root>/...` descendant.
   *
   * Manage on a context root authorizes the root and its slash-delimited descendants. Route
   * through the validator instead of raw string ops: a malformed scope like `<pod-base>#manage`
   * (pod root, not a context inside the pod) is rejected by the validator and therefore cannot
   * match here, even if it slipped past
   * [org.sempods.pods.grants.GrantStorePodAuthorizer]'s scope sanitizing on a code path we have
   * yet to harden. Raw `startsWith` on the bare scope string would otherwise let it match every
   * context under `<pod-base>`. The validator also enforces the slash-delimited boundary by
   * construction, so `tasks#manage` still doesn't reach `tasks-private`.
   *
   * Load-bearing — see `SPS-GRANT-007` (sempods-spec). Thin wrapper over
   * [PodContextPermissionResolver.isCoveredByManageScope], the single source of truth for the
   * slash-delimited rule, shared with [org.sempods.pods.grants.GrantStorePodAuthorizer]'s
   * read-path manage-cascade and
   * [org.sempods.api.pod.system.contexts.PodContextsEndpoint] (context PUT/DELETE), so the
   * paths cannot drift.
   */
  fun isCoveredByManageScope(credentials: SempodsCredentials, contextUri: URI): Boolean {
    val podBaseUrl = "${credentials.pod.uri}/"
    return podContextPermissionResolver.isCoveredByManageScope(credentials.oauthScopes, podBaseUrl, contextUri)
  }

  /**
   * The context registry lookup both gates share. The pod's storage key is resolved from its name
   * through [PodFacade.getPodId] rather than taken from a passed-in row: the callers hold
   * [org.sempods.spec.PodRef]-shaped credentials now, and the lookup is cached.
   */
  private fun contextIsRegistered(pod: String, contextUri: URI): Boolean {
    val podId = podFacade.getPodId(pod) ?: return false
    return podContextsDao.exists(podId = podId, contextUri = contextUri.toString())
  }
}
