package org.sempods.api.pod.system.contexts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import org.sempods.SempodsUriBuilder
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.pods.contexts.persist.PodContextDbo
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.ContextPermissionEntry
import org.sempods.pods.grants.PodContextPermissionResolver
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI

/**
 * RESTful management of pod contexts.
 *
 * - `GET    {pod}/_system/contexts`             — list visible contexts.
 * - `PUT    {pod}/_system/contexts/{path...}`   — create or no-op a context.
 * - `DELETE {pod}/_system/contexts/{path...}`   — cascade-remove a context.
 *
 * `PUT` / `DELETE` are authorized for the pod owner (catch-all) or for a service
 * client holding a `<root>#manage` scope that covers the target context via the
 * slash-delimited rule (`authorization.md` §"manage semantics") — the same rule the
 * write enforcer applies, shared through
 * [org.sempods.api.pod.resources.PodContextWriteAuthorizer.isCoveredByManageScope].
 *
 * The `{path...}` segment **is** the context IRI's path: the API path
 * `{pod}/_system/contexts/apps/notes/public` manages the context whose
 * URI is `{pod}/_system/contexts/apps/notes/public`. Contexts live inside
 * the reserved `_system` tree because they are control-plane state, not data —
 * sempods-spec `spec/core/contexts.md` §2 — so
 * identity and management route are one string and cannot drift apart.
 *
 * Structure rules live in [org.sempods.pods.contexts.ContextPathRules] and are shared with
 * [org.sempods.api.pod.system.auth.PodAuthEndpoint], the other producer of context IRIs.
 * They apply on `PUT` only: an existing context of any shape stays readable and deletable.
 *
 * Cascade on DELETE lives in [org.sempods.pods.PodFacade.removeContext]:
 * revokes refresh tokens scoped to the context, drops the matching grants,
 * strips the context from RDF resources, removes the
 * [org.sempods.pods.contexts.persist.PodContextDbo] row.
 */
@Path("{pod}/_system/contexts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class PodContextsEndpoint @Inject constructor(
  private val podContextsDao: PodContextsDao,
  private val contextPermissionResolver: PodContextPermissionResolver,
  private val contextWriteAuthorizer: PodContextWriteAuthorizer,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @PUT
  @Path("{contextPath: .+}")
  fun put(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    request: PutPodContextRequest?,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    requireCreatableContextPathOrThrow(ContextPathRules.normalize(contextPath))
    val createdBy = authorizeContextManageOrThrow(pod = pod, podDbo = podDbo, contextUri = contextUri)
    val podId = checkNotNull(podDbo.id)
    val body = request ?: PutPodContextRequest()

    val existing = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
    if (existing != null) {
      // PUT is idempotent: an existing context is a no-op. Field-level updates
      // (label/description/public toggle) are intentionally out of scope here;
      // see the in-file TODO below for the owner-facing visibility toggle.
      return Response.ok(existing.toPutResponse()).build()
    }

    val created = podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = body.label?.trim()?.ifBlank { null },
      description = body.description?.trim()?.ifBlank { null },
      createdBy = createdBy,
      isPublic = body.public,
    )
    if (created == null) {
      // Race: another caller created the same row between the existence check inside `create`
      // and its insert. PUT is idempotent and the caller's post-condition holds, so this is the
      // same 200 the pre-existing-row branch above returns — not an error.
      val won = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
        ?: throw WebApplicationException(
          Response.status(500).entity("unexpected create failure for $contextUri").type("text/plain").build()
        )
      return Response.ok(won.toPutResponse()).build()
    }
    return Response.status(201).entity(created.toPutResponse()).build()
  }

  /**
   * The context itself, at its own IRI — `GET {pod}/_system/contexts/apps/notes/public` returns
   * what the registry holds for `{pod}/_system/contexts/apps/notes/public`.
   *
   * This is what makes a context IRI dereferenceable, and it is what separates the *original* from
   * what anyone may say *about* it. Triples whose subject is a context IRI are ordinary statements
   * living in some context — the same way a pod can hold statements about `did:web:bob.example` or
   * another pod's resources (`lod-crud/lod-layer.md` §"Writes": resource IRI and target graph are
   * independent dimensions). They are read back through `_system/resources/{b64url(iri)}`. What the
   * registry says about the context comes from here, and RDF cannot change it: contexts, grants and
   * registrations live in MongoDB, not in the graph.
   *
   * Visibility follows the listing: a caller sees a context exactly when [list] would include it,
   * and gets 404 otherwise — never a 403, which would confirm that the context exists.
   */
  // TODO: this route also swallows *resources* whose IRI happens to sit below a context IRI —
  //  `{pod}/_system/contexts/tasks/res-1` matches here and answers JSON, so an RDF-accepting client
  //  gets a 406 instead of the resource. `create_resource` over MCP mints exactly such IRIs when the
  //  caller derives them from `context_iri`. Either decide that a subject under the reserved context
  //  namespace is not addressable at the LOD layer and say so (400 on the write), or fall through to
  //  `PodResourceEndpoint` when the path does not name a registered context.
  @GET
  @Path("{contextPath: .+}")
  fun get(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
  ): Response {
    val credentials = authenticate(pod)
    val podId = checkNotNull(podFacade.getPodId(credentials.pod.name))
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"

    val dbo = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
      ?: throw WebApplicationException(
        Response.status(404).entity("unknown context").type("text/plain").build()
      )

    val effective = contextPermissionResolver.describeEffectivePermissions(
      effectiveScopes = credentials.oauthScopes,
      rawScopes = credentials.oauthRawScopes,
      visibleContexts = credentials.restrictedContexts.orEmpty(),
      podBaseUrl = podBaseUrl,
    )
    val entry = effective.byContext[dbo.contextUri]
      ?: throw WebApplicationException(
        Response.status(404).entity("unknown context").type("text/plain").build()
      )

    return Response.ok(dbo.toResponse(entry)).build()
  }

  @GET
  fun list(
    @PathParam("pod") pod: String,
  ): Response {
    val credentials = authenticate(pod)
    val podId = checkNotNull(podFacade.getPodId(credentials.pod.name))
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"

    // Effective context permissions are resolved server-side per request from durable grants
    // through the shared resolver — the same logic MCP `list_contexts` uses — so REST and MCP
    // cannot drift.
    val effective = contextPermissionResolver.describeEffectivePermissions(
      effectiveScopes = credentials.oauthScopes,
      rawScopes = credentials.oauthRawScopes,
      visibleContexts = credentials.restrictedContexts.orEmpty(),
      podBaseUrl = podBaseUrl,
    )

    val contexts = podContextsDao.fetchByPod(podId)
      .mapNotNull { dbo -> effective.byContext[dbo.contextUri]?.let { dbo.toResponse(it) } }

    return Response.ok(
      PodContextsListResponse(
        podBaseUrl = "${config.apiBaseUrl}$pod",
        authenticated = credentials.oauthClientId != null,
        contexts = contexts,
        writableContexts = effective.writableContexts,
      )
    ).build()
  }

  @DELETE
  @Path("{contextPath: .+}")
  fun delete(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    // Authorize before the existence check so an out-of-sandbox caller gets 403, not a
    // 404 that would leak whether the context exists.
    authorizeContextManageOrThrow(pod = pod, podDbo = podDbo, contextUri = contextUri)
    val podId = checkNotNull(podDbo.id)
    if (!podContextsDao.exists(podId = podId, contextUri = contextUri.toString())) {
      throw WebApplicationException(
        Response.status(404).entity("unknown context").type("text/plain").build()
      )
    }
    podFacade.removeContext(podName = pod, context = contextUri)
    return Response.noContent().build()
  }

  /**
   * Authorize a context create/delete and return the subject to record as `createdBy`.
   *
   * Two allow paths, both read off the same pod access token:
   * 1. Pod owner — catch-all allow, may manage any context. Ownership is implicit: it follows
   *    from being the owner, not from a grant or a scope, so it is decided before any scope is
   *    looked at. An owner with nothing granted still passes, which is what lets a fresh pod get
   *    its first context.
   * 2. Service client / OAuth caller holding a `<root>#manage` scope that covers [contextUri]
   *    via the slash-delimited rule shared with the write enforcer
   *    ([PodContextWriteAuthorizer.isCoveredByManageScope]).
   *
   * [authenticate] validates the pod OAuth token (401 on invalid/expired/foreign) and records
   * the service-client audit row, so this path stays consistent with the resource layer. It runs
   * first now — the owner check used to short-circuit ahead of it because it read a *different*
   * credential, an id-server identity JWT presented as a bearer. One resolution instead of two,
   * and no JWKS fetch against another host on the way.
   *
   * Anonymous callers resolve to empty scopes and no subject → 401. An authenticated caller
   * outside its sandbox → 403.
   */
  private fun authorizeContextManageOrThrow(pod: String, podDbo: PodDbo, contextUri: URI): String {
    val credentials = authenticate(pod)
    resolvePodOwnerPrincipal(credentials)?.let { return it.toSubject() }

    if (contextWriteAuthorizer.isCoveredByManageScope(credentials, contextUri)) {
      return credentials.tokenSub
        ?: credentials.oauthClientId
        ?: throw WebApplicationException(
          Response.status(401).entity("missing or invalid credentials").type("text/plain").build()
        )
    }

    val status = if (credentials.oauthClientId == null) 401 else 403
    val message = if (status == 401) {
      "missing or invalid credentials"
    } else {
      "missing manage permission for context '$contextUri'"
    }
    throw WebApplicationException(
      Response.status(status).entity(message).type("text/plain").build()
    )
  }

  /**
   * Maps the request path onto the canonical context IRI. Since contexts moved into the reserved
   * area the two are the same string — `PUT {pod}/_system/contexts/apps/notes/public` addresses
   * `{pod}/_system/contexts/apps/notes/public`, so there is no decomposition here and no
   * recomposition on the client side that could drift apart.
   *
   * Built by [ContextPathRules.resolve], shared with the consent dialog — the prefix is prepended
   * there, never taken from the caller, and a path that cannot be an addressable IRI (fragment,
   * query, broken syntax) is rejected. Applies to every verb: a string that is not addressable was
   * never a context, whenever it was written.
   *
   * What a caller may *name* is [requireCreatableContextPathOrThrow], and that runs on `PUT` only.
   */
  private fun resolveContextUri(pod: String, contextPath: String): URI =
    when (val resolution = ContextPathRules.resolve("${config.apiBaseUrl}${pod}/", contextPath)) {
      is ContextUriResolution.Resolved -> resolution.uri
      is ContextUriResolution.Rejected -> throw badContextPath(resolution.reason)
    }

  /**
   * Structure rules for a context path, shared with the consent dialog — see [ContextPathRules].
   *
   * Applied on `PUT` only, deliberately. Structure rules govern what may come into existence;
   * reading and deleting must keep working for everything that already exists, including contexts
   * predating these rules and the type roots the control plane sets up. A `GET` that refused an
   * existing context, or a `DELETE` that could not remove a root it had created, would be a
   * one-way door.
   */
  private fun requireCreatableContextPathOrThrow(path: String) {
    ContextPathRules.rejectionReason(path)?.let { throw badContextPath(it) }
  }

  private fun badContextPath(message: String): WebApplicationException =
    WebApplicationException(Response.status(400).entity(message).type("text/plain").build())

  private fun PodContextDbo.toResponse(entry: ContextPermissionEntry): PodContextResponse {
    return PodContextResponse(
      contextIri = contextUri,
      permissions = entry.permissions,
      source = entry.source.value,
      label = label,
      description = description,
      public = isPublic,
      createdAt = createdAt.toString(),
    )
  }

  private fun PodContextDbo.toPutResponse(): PutPodContextResponse {
    return PutPodContextResponse(
      contextIri = contextUri,
      label = label,
      description = description,
      public = isPublic,
      createdAt = createdAt.toString(),
    )
  }
}

data class PutPodContextRequest(
  val label: String? = null,
  val description: String? = null,
  /**
   * Marks the context anonymously readable on creation. Defaults to `false`
   * (private). Owner-controlled — same gate as [PodContextsEndpoint.put].
   */
  val public: Boolean = false,
)

// TODO: Schnitt 2 — owner-facing visibility toggle for existing contexts
//   (PATCH {pod}/_system/contexts/{path} { "public": bool }, owner-gated like
//   delete) plus a `public` checkbox in the consent UI's newContexts flow.

/**
 * One context entry in the `GET _system/contexts` listing. Mirrors the MCP `list_contexts`
 * shape (`context_iri`, `permissions`, `source`) plus REST-only metadata (`label`,
 * `description`, `public`, `createdAt`).
 */
data class PodContextResponse(
  @field:JsonProperty("context_iri")
  val contextIri: String,

  val permissions: List<String>,

  val source: String,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val label: String?,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val description: String?,

  val public: Boolean = false,

  val createdAt: String,
)

data class PodContextsListResponse(
  @field:JsonProperty("pod_base_url")
  val podBaseUrl: String,

  val authenticated: Boolean,

  val contexts: List<PodContextResponse>,

  @field:JsonProperty("writable_contexts")
  val writableContexts: List<String>,
)

/** Response for `PUT _system/contexts/{path}` — the created/existing context's metadata. */
data class PutPodContextResponse(
  @field:JsonProperty("context_iri")
  val contextIri: String,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val label: String?,

  @field:[JsonProperty JsonInclude(JsonInclude.Include.NON_EMPTY)]
  val description: String?,

  val public: Boolean = false,

  val createdAt: String,
)
