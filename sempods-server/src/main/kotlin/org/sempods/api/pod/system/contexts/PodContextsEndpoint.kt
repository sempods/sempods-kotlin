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
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.PodContextPermissionResolver
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.flows.PodOwnerAuthority
import org.sempods.pods.oauth.flows.PodOwnerAuthorityCheck
import org.sempods.pods.oauth.flows.PodOwnerAuthorityRefusal
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.util.Values
import org.sempods.pods.ResourceValidator
import org.sempods.rdf.RdfWriterUtil
import java.net.URI

/**
 * RESTful management of pod contexts.
 *
 * - `GET    {pod}/_system/contexts`             — list visible contexts.
 * - `PUT    {pod}/_system/contexts/{path...}`   — create or no-op a context.
 * - `DELETE {pod}/_system/contexts/{path...}`   — cascade-remove a context.
 *
 * `PUT` / `DELETE` are authorized for a caller holding a `<root>#manage` grant that covers the
 * target context via the slash-delimited rule (`SPS-GRANT-007` (sempods-spec)) — the same rule the
 * write enforcer applies, shared through
 * [org.sempods.api.pod.resources.PodContextWriteAuthorizer.isCoveredByManageScope] — or for a bearer
 * carrying the owner's approved [CONTEXTS_MANAGE_SCOPE], which reaches every context
 * (`SPS-CTX-019`). A bearer whose subject owns the pod is otherwise an application like any other.
 * The catalogue shows the [CONTEXTS_MANAGE_SCOPE] bearer every registered context as `manage` alone
 * ([holdsRegistryAuthority]).
 *
 * **The registry answers RDF.** `GET` at the catalogue and at a context IRI produce canonical
 * JSON-LD by default and N-Quads on request (`SPS-CTX-031`), a successful `PUT` answers the created
 * or existing context's description (`SPS-CTX-037`), and every answer carries a strong validator and
 * `Cache-Control: no-store` (`SPS-CTX-035`, `SPS-CTX-036`). `application/json` still answers the JSON
 * envelopes this route used to, which `SPS-CTX-031` rules out and
 * [#184](https://github.com/sempods/sempods-kotlin/issues/184) removes; a response carrying them says
 * so with `Deprecation: true`.
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
 * drops the matching grants, strips the context from RDF resources, removes the
 * [org.sempods.pods.contexts.persist.PodContextDbo] row.
 */
@Path("{pod}/_system/contexts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
class PodContextsEndpoint @Inject constructor(
  private val podContextsDao: PodContextsDao,
  private val contextPermissionResolver: PodContextPermissionResolver,
  private val contextWriteAuthorizer: PodContextWriteAuthorizer,
  private val ownerAuthority: PodOwnerAuthority,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @PUT
  @Path("{contextPath: .+}")
  @Produces("application/ld+json", "application/json", "application/n-quads")
  fun put(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    @Context httpHeaders: HttpHeaders,
    body: PutPodContextRequest?,
  ): Response {
    // Negotiated before anything is written: Jersey has already answered an unsatisfiable `Accept`
    // with 406 during matching, so no context comes into existence for one (`SPS-CTX-037`).
    val format = ContextRegistryNegotiation.select(httpHeaders.getHeaderString(HttpHeaders.ACCEPT)) ?: return notAcceptable()
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"
    val podDbo = fetchPodOrThrow(pod)
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    // Authority before the naming rules, which hold for creation only: a caller without it gets 403
    // whether the context exists or not (`SPS-CORE-018`).
    val createdBy = authorizeContextManageOrThrow(pod = pod, podDbo = podDbo, contextUri = contextUri)
    requireCreatableContextPathOrThrow(ContextPathRules.normalize(contextPath))
    val podId = checkNotNull(podDbo.id)
    val fields = body ?: PutPodContextRequest()

    val existing = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
    if (existing != null) {
      // PUT is idempotent: an existing context is a no-op. Field-level updates
      // (label/description/public toggle) are intentionally out of scope here.
      return contextResponse(status = 200, row = existing, format = format, podBaseUrl = podBaseUrl)
    }

    val created = podContextsDao.create(
      podId = podId,
      contextUri = contextUri.toString(),
      label = fields.label?.trim()?.ifBlank { null },
      description = fields.description?.trim()?.ifBlank { null },
      createdBy = createdBy,
      isPublic = fields.public,
    )
    if (created == null) {
      // Race: another caller created the same row between the existence check inside `create`
      // and its insert. PUT is idempotent and the caller's post-condition holds, so this is the
      // same 200 the pre-existing-row branch above returns — not an error.
      val won = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
        ?: throw WebApplicationException(
          Response.status(500).entity("unexpected create failure for $contextUri").type("text/plain").build()
        )
      return contextResponse(status = 200, row = won, format = format, podBaseUrl = podBaseUrl)
    }
    return contextResponse(status = 201, row = created, format = format, podBaseUrl = podBaseUrl)
  }

  /**
   * The context itself, at its own IRI — `GET {pod}/_system/contexts/apps/notes/public` returns
   * what the registry holds for `{pod}/_system/contexts/apps/notes/public`.
   *
   * This is what makes a context IRI dereferenceable. What the registry says about the context comes
   * from here, and RDF cannot change it: contexts, grants and registrations live in MongoDB, not in
   * the graph. Every path below `_system/contexts/` lands here, so a write about a subject under it
   * is refused ([ContextPathRules.reservedSubjectReason]): no resource shares an IRI with a context.
   *
   * Visibility follows the listing: a caller sees a context exactly when [list] would include it,
   * and gets 404 otherwise — never a 403, which would confirm that the context exists.
   */
  @GET
  @Path("{contextPath: .+}")
  @Produces("application/ld+json", "application/json", "application/n-quads")
  fun get(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
    @Context httpHeaders: HttpHeaders,
  ): Response {
    val credentials = authenticate(pod)
    // Before the lookup: a withdrawn authority answers the same for a context that exists and one that does not.
    val registryAuthority = holdsRegistryAuthority(pod, credentials)
    val podId = checkNotNull(podFacade.getPodId(credentials.pod.name))
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"

    val dbo = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
      ?: throw unknownContext()

    val effective = contextPermissionResolver.describeEffectivePermissions(
      effectiveScopes = credentials.oauthScopes,
      rawScopes = credentials.oauthRawScopes,
      visibleContexts = credentials.restrictedContexts.orEmpty(),
      podBaseUrl = podBaseUrl,
      registryContexts = if (registryAuthority) listOf(dbo.contextUri) else emptyList(),
    )
    val entry = effective.byContext[dbo.contextUri]
      ?: throw unknownContext()

    // Authorization and the normal status come first, the precondition last: a caller whose read was
    // revoked gets the same 404 an unregistered path gets, never a 304 off the tag they still hold
    // (`SPS-CTX-035`).
    val format = ContextRegistryNegotiation.select(httpHeaders.getHeaderString(HttpHeaders.ACCEPT)) ?: return notAcceptable()
    val model = PodContextRegistryRdf.describe(row = dbo, podBaseUrl = podBaseUrl)
    return registryRead(format, model, Values.iri(dbo.contextUri)) { dbo.toResponse(entry) }
  }

  @GET
  @Produces("application/ld+json", "application/json", "application/n-quads")
  fun list(
    @PathParam("pod") pod: String,
    @Context httpHeaders: HttpHeaders,
  ): Response {
    val credentials = authenticate(pod)
    val podId = checkNotNull(podFacade.getPodId(credentials.pod.name))
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"

    val rows = podContextsDao.fetchByPod(podId)
    // Effective context permissions are resolved server-side per request from durable grants
    // through the shared resolver — the same logic MCP `list_contexts` uses — so REST and MCP
    // cannot drift.
    val effective = contextPermissionResolver.describeEffectivePermissions(
      effectiveScopes = credentials.oauthScopes,
      rawScopes = credentials.oauthRawScopes,
      visibleContexts = credentials.restrictedContexts.orEmpty(),
      podBaseUrl = podBaseUrl,
      registryContexts = if (holdsRegistryAuthority(pod, credentials)) rows.map { it.contextUri } else emptyList(),
    )

    val format = ContextRegistryNegotiation.select(httpHeaders.getHeaderString(HttpHeaders.ACCEPT)) ?: return notAcceptable()
    val model = PodContextRegistryRdf.catalogue(podBaseUrl = podBaseUrl, rows = rows, effective = effective)
    return registryRead(format, model, PodContextRegistryRdf.catalogueIri(podBaseUrl)) {
      PodContextsListResponse(
        podBaseUrl = "${config.apiBaseUrl}$pod",
        authenticated = credentials.oauthClientId != null,
        contexts = rows.mapNotNull { dbo -> effective.byContext[dbo.contextUri]?.let { dbo.toResponse(it) } },
        writableContexts = effective.writableContexts,
      )
    }
  }

  @DELETE
  @Path("{contextPath: .+}")
  fun delete(
    @PathParam("pod") pod: String,
    @PathParam("contextPath") contextPath: String,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    val contextUri = resolveContextUri(pod = pod, contextPath = contextPath)
    // Before the existence check: a caller without authority gets 403 whether the context exists or
    // not (`SPS-CORE-018`).
    authorizeContextManageOrThrow(pod = pod, podDbo = podDbo, contextUri = contextUri)
    val podId = checkNotNull(podDbo.id)
    if (!podContextsDao.exists(podId = podId, contextUri = contextUri.toString())) {
      throw unknownContext()
    }
    podFacade.removeContext(podName = pod, context = contextUri)
    return Response.noContent().build()
  }

  /**
   * Whether [credentials] hold the owner's [CONTEXTS_MANAGE_SCOPE] authority, so the catalogue
   * reports every registered context to them as `manage` and nothing more.
   *
   * Checked as `PUT` checks it, so the scope alone reports nothing. An authority withdrawn by a
   * disconnect is `401 invalid_token` here too. One whose person no longer owns the pod is `false`,
   * which is all that bearer holds.
   */
  private fun holdsRegistryAuthority(pod: String, credentials: SempodsCredentials): Boolean {
    if (CONTEXTS_MANAGE_SCOPE !in credentials.oauthScopes) return false
    return when (val check = ownerAuthority.check(fetchPodOrThrow(pod).hosted, credentials, CONTEXTS_MANAGE_SCOPE)) {
      is PodOwnerAuthorityCheck.Standing -> true
      is PodOwnerAuthorityCheck.Refused ->
        if (check.reason == PodOwnerAuthorityRefusal.NOT_OWNER) {
          false
        } else {
          throw WebApplicationException(ownerAuthorityRefused(pod, check.reason, CONTEXTS_MANAGE_SCOPE, manages = "contexts"))
        }
    }
  }

  /**
   * Authorize a context create/delete and return the subject to record as `createdBy`.
   *
   * Two allow paths, both read off the same pod access token:
   * 1. The owner's own authority, through a bearer carrying [CONTEXTS_MANAGE_SCOPE] whose recorded
   *    authority still stands and names the pod's current owner ([PodOwnerAuthority]). It reaches
   *    every context, registered or not, and needs nothing granted — which is what lets a fresh pod
   *    get its first context from a program.
   * 2. A `<root>#manage` grant covering [contextUri] via the slash-delimited rule shared with the
   *    write enforcer ([PodContextWriteAuthorizer.isCoveredByManageScope]) — a service client, or
   *    an app the owner or another person delegated it to.
   *
   * **Ownership alone is neither.** A bearer whose `sub` owns the pod is an application holding
   * what was approved for it (`SPS-GRANT-011`, `SPS-GRANT-013`); an app approved for one context
   * must not delete the rest because the person behind its token could.
   *
   * [authenticate] validates the pod OAuth token (401 on invalid/expired/foreign) and records the
   * service-client audit row. Anonymous callers → 401; a withdrawn [CONTEXTS_MANAGE_SCOPE]
   * authority → 401; an authenticated caller outside its sandbox → 403.
   */
  private fun authorizeContextManageOrThrow(pod: String, podDbo: PodDbo, contextUri: URI): String {
    val credentials = authenticate(pod)

    if (CONTEXTS_MANAGE_SCOPE in credentials.oauthScopes) {
      when (val check = ownerAuthority.check(podDbo.hosted, credentials, CONTEXTS_MANAGE_SCOPE)) {
        is PodOwnerAuthorityCheck.Standing -> return check.authority.webId
        is PodOwnerAuthorityCheck.Refused ->
          throw WebApplicationException(ownerAuthorityRefused(pod, check.reason, CONTEXTS_MANAGE_SCOPE, manages = "contexts"))
      }
    }

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

  /** A created or existing context, as its registry description (`SPS-CTX-037`). */
  private fun contextResponse(status: Int, row: PodContextDbo, format: RegistryFormat, podBaseUrl: String): Response {
    val model = PodContextRegistryRdf.describe(row = row, podBaseUrl = podBaseUrl)
    val builder = Response.status(status)
      .entity(registryEntity(format, model, Values.iri(row.contextUri)) { row.toPutResponse() })
      .type(format.contentType)
    // No `ETag` here: `SPS-CTX-037` gives a write no validator, and a tag would invite an `If-Match`
    // this route does not evaluate.
    return deprecationHeaders(builder, format).build()
  }

  /**
   * A registry read: the representation, its validator and the conditional answer.
   *
   * **The transitional envelope carries no validator.** It states the caller's own permissions,
   * which the registry RDF does not, so a tag hashed from the model would stay put while the
   * envelope changed — and a replayed `If-None-Match` would answer `304` for a body that moved. It
   * gets what this route gave before the registry became RDF: no tag, no conditional read, until
   * [#184](https://github.com/sempods/sempods-kotlin/issues/184) removes the shape.
   */
  private fun registryRead(format: RegistryFormat, model: Model, subject: IRI, legacy: () -> Any): Response {
    if (format == RegistryFormat.LEGACY_JSON) {
      return deprecationHeaders(Response.ok(legacy()).type(format.contentType), format).build()
    }
    val tag = registryTag(model = model, format = format)
    evaluatePreconditions(tag)?.let { return deprecationHeaders(Response.fromResponse(it), format).build() }
    val entity = registryEntity(format, model, subject) { legacy() }
    return deprecationHeaders(Response.ok(entity).type(format.contentType).tag(tag), format).build()
  }

  private fun registryEntity(format: RegistryFormat, model: Model, subject: IRI, legacy: () -> Any): Any =
    when (format) {
      RegistryFormat.JSON_LD -> RdfWriterUtil.toCanonicalJsonLdEntry(model, subject, RdfWriterUtil.CanonicalJsonLd.REGISTRY)
      RegistryFormat.N_QUADS -> StreamingOutput { out -> RdfWriterUtil.streamNQuads(out, model) }
      RegistryFormat.LEGACY_JSON -> legacy()
    }

  /**
   * The representation's strong validator (`SPS-CTX-035`).
   *
   * Hashed over exactly the triples that go out, so it changes when membership, rights or metadata
   * change and stays put when the graph does — the registry's view of a context is what it
   * validates. [createContentTypeAwareEntityTag] keeps the three representations apart.
   */
  private fun registryTag(model: Model, format: RegistryFormat): EntityTag =
    createContentTypeAwareEntityTag(ResourceValidator.compute(model), format.contentType)

  /**
   * What the transitional envelope owes on its own. `Cache-Control` and `Vary` belong to the whole
   * route and are set by [ContextRegistryCacheFilter], which also reaches the answers no method here
   * builds.
   */
  private fun deprecationHeaders(builder: Response.ResponseBuilder, format: RegistryFormat): Response.ResponseBuilder {
    if (format == RegistryFormat.LEGACY_JSON) {
      builder.header("Deprecation", "true")
        .header(HttpHeaders.LINK, "<$DEPRECATION_ISSUE>; rel=\"deprecation\"")
    }
    return builder
  }

  /** Nothing this route produces is acceptable. On `PUT` this is reached before anything is written. */
  private fun notAcceptable(): Response = Response.status(Response.Status.NOT_ACCEPTABLE)
    .entity("the context registry answers application/ld+json, application/n-quads or application/json")
    .type(MediaType.TEXT_PLAIN)
    .build()

  /** Hidden and absent are one answer, down to the headers and the missing validator (`SPS-CTX-035`). */
  private fun unknownContext(): WebApplicationException = WebApplicationException(
    Response.status(404).entity("unknown context").type(MediaType.TEXT_PLAIN).build()
  )

  private companion object {

    const val DEPRECATION_ISSUE = "https://github.com/sempods/sempods-kotlin/issues/184"
  }
}

data class PutPodContextRequest(
  val label: String? = null,
  val description: String? = null,
  /**
   * Marks the context anonymously readable on creation. Defaults to `false`
   * (private). Owner-controlled — same gate as [PodContextsEndpoint.put].
   *
   * The default is the contract, not a convenience: `SPS-CTX-027` (sempods-spec) requires an
   * absent flag to create a private context, a request with no body at all included, and the
   * body is optional. `PodContextsEndpointHttpTest` pins both quiet paths.
   */
  val public: Boolean = false,
)

// TODO: Schnitt 2 — owner-facing visibility toggle for existing contexts
//   (PATCH {pod}/_system/contexts/{path} { "public": bool }, owner-gated like
//   delete) plus a `public` checkbox in the consent UI's newContexts flow.

/**
 * One context entry in the transitional `application/json` listing. Mirrors the MCP `list_contexts`
 * shape (`context_iri`, `permissions`, `source`) plus REST-only metadata (`label`, `description`,
 * `public`, `createdAt`).
 *
 * `SPS-CTX-033` states a caller's rights as RDF relations instead, so this type and its two siblings
 * go with [#184](https://github.com/sempods/sempods-kotlin/issues/184).
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
