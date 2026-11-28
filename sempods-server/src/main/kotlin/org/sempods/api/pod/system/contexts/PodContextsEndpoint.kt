package org.sempods.api.pod.system.contexts

import org.sempods.api.PodBearerRoutes
import org.sempods.api.PodBaseEndpoint
import org.sempods.SempodsConfig
import jakarta.ws.rs.container.ContainerRequestContext
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import org.sempods.pods.contexts.ContextManagementSupport
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
import jakarta.ws.rs.*
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.util.Values
import org.sempods.pods.ResourceValidator

/**
 * The context registry's reads: the catalogue and each context's description, which every pod serves
 * (sempods-spec `spec/core/contexts.md`).
 *
 * - `GET {pod}/_system/contexts`           — the caller's catalogue.
 * - `GET {pod}/_system/contexts/{path...}` — one context, at its own IRI.
 *
 * **Creating and deleting a context is not here.** That is the spec's optional `context-management`
 * module, served by `:sempods-context-management` on the same path through
 * [PodContextLifecycleRoutes]. Without it the catalogue reports no `manageableContext` and no
 * caller holds the registry authority below ([ContextManagementSupport]).
 *
 * The catalogue shows a bearer carrying the owner's approved [CONTEXTS_MANAGE_SCOPE] every registered
 * context as `manage` alone ([holdsRegistryAuthority]).
 *
 * **The registry answers RDF.** `GET` at the catalogue and at a context IRI produce canonical
 * JSON-LD by default and N-Quads on request (`SPS-CTX-031`), and every answer carries a strong
 * validator and `Cache-Control: no-store` (`SPS-CTX-035`, `SPS-CTX-036`). `application/json` still
 * answers the JSON envelopes this route used to, which `SPS-CTX-031` rules out and
 * [#184](https://github.com/sempods/sempods-kotlin/issues/184) removes; a response carrying them says
 * so with `Deprecation: true`.
 *
 * The `{path...}` segment **is** the context IRI's path: `{pod}/_system/contexts/apps/notes/public`
 * is the context whose URI is `{pod}/_system/contexts/apps/notes/public`. Contexts live inside the
 * reserved `_system` tree because they are control-plane state, not data — sempods-spec
 * `spec/core/contexts.md` §2 — so identity and route are one string and cannot drift apart.
 */
@Path("{pod}/_system/contexts")
@Produces(MediaType.APPLICATION_JSON)
class PodContextsEndpoint @Inject internal constructor(
  private val podContextsDao: PodContextsDao,
  private val contextPermissionResolver: PodContextPermissionResolver,
  private val ownerAuthority: PodOwnerAuthority,
  private val podFacade: PodFacade,
  private val bearerRoutes: PodBearerRoutes,
  private val contextManagement: ContextManagementSupport,
  private val config: SempodsConfig,
) : PodBaseEndpoint() {

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
    @Context requestContext: ContainerRequestContext,
  ): Response {
    val credentials = authenticate(pod, requestContext)
    // Before the lookup: a withdrawn authority answers the same for a context that exists and one that does not.
    val registryAuthority = holdsRegistryAuthority(pod, credentials)
    val podId = checkNotNull(podFacade.getPodId(credentials.pod.name))
    val contextUri = ContextRegistryResponses.contextUri("${config.apiBaseUrl}${pod}/", contextPath)
    val podBaseUrl = "${config.apiBaseUrl}${pod}/"

    val dbo = podContextsDao.fetchByContextUri(podId = podId, contextUri = contextUri.toString())
      ?: throw ContextRegistryResponses.unknownContext()

    val effective = contextPermissionResolver.describeEffectivePermissions(
      effectiveScopes = credentials.oauthScopes,
      rawScopes = credentials.oauthRawScopes,
      visibleContexts = credentials.restrictedContexts.orEmpty(),
      podBaseUrl = podBaseUrl,
      registryContexts = if (registryAuthority) listOf(dbo.contextUri) else emptyList(),
    )
    val entry = effective.byContext[dbo.contextUri]
      ?: throw ContextRegistryResponses.unknownContext()

    // Authorization and the normal status come first, the precondition last: a caller whose read was
    // revoked gets the same 404 an unregistered path gets, never a 304 off the tag they still hold
    // (`SPS-CTX-035`).
    val format = ContextRegistryNegotiation.select(httpHeaders.getHeaderString(HttpHeaders.ACCEPT)) ?: return ContextRegistryResponses.notAcceptable()
    val model = PodContextRegistryRdf.describe(row = dbo, podBaseUrl = podBaseUrl)
    return registryRead(requestContext, format, model, Values.iri(dbo.contextUri)) { dbo.toResponse(entry) }
  }

  @GET
  @Produces("application/ld+json", "application/json", "application/n-quads")
  fun list(
    @PathParam("pod") pod: String,
    @Context httpHeaders: HttpHeaders,
    @Context requestContext: ContainerRequestContext,
  ): Response {
    val credentials = authenticate(pod, requestContext)
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

    val format = ContextRegistryNegotiation.select(httpHeaders.getHeaderString(HttpHeaders.ACCEPT)) ?: return ContextRegistryResponses.notAcceptable()
    val model = PodContextRegistryRdf.catalogue(
      podBaseUrl = podBaseUrl,
      rows = rows,
      effective = effective,
      manageable = contextManagement.offered,
    )
    return registryRead(requestContext, format, model, PodContextRegistryRdf.catalogueIri(podBaseUrl)) {
      PodContextsListResponse(
        podBaseUrl = "${config.apiBaseUrl}$pod",
        authenticated = credentials.authenticated,
        contexts = rows.mapNotNull { dbo -> effective.byContext[dbo.contextUri]?.let { dbo.toResponse(it) } },
        writableContexts = effective.writableContexts,
      )
    }
  }

  /**
   * Whether [credentials] hold the owner's [CONTEXTS_MANAGE_SCOPE] authority, so the catalogue
   * reports every registered context to them as `manage` and nothing more.
   *
   * Never without the lifecycle module: the authority is the owner's over the registry's lifecycle,
   * and a pod that serves none has nothing to report it for. With it, checked as `PUT` checks it, so
   * the scope alone reports nothing. An authority withdrawn by a
   * disconnect is `401 invalid_token` here too. One whose person no longer owns the pod is `false`,
   * which is all that bearer holds.
   */
  private fun holdsRegistryAuthority(pod: String, credentials: SempodsCredentials): Boolean {
    if (!contextManagement.offered || CONTEXTS_MANAGE_SCOPE !in credentials.oauthScopes) return false
    return when (val check = ownerAuthority.check(bearerRoutes.hosted(bearerRoutes.fetchPodOrThrow(pod)), credentials, CONTEXTS_MANAGE_SCOPE)) {
      is PodOwnerAuthorityCheck.Standing -> true
      is PodOwnerAuthorityCheck.Refused ->
        if (check.reason == PodOwnerAuthorityRefusal.NOT_OWNER) {
          false
        } else {
          throw WebApplicationException(bearerRoutes.ownerAuthorityRefused(pod, check.reason, CONTEXTS_MANAGE_SCOPE, manages = "contexts"))
        }
    }
  }

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

  /**
   * A registry read: the representation, its validator and the conditional answer.
   *
   * **The transitional envelope carries no validator.** It states the caller's own permissions,
   * which the registry RDF does not, so a tag hashed from the model would stay put while the
   * envelope changed — and a replayed `If-None-Match` would answer `304` for a body that moved. It
   * gets what this route gave before the registry became RDF: no tag, no conditional read, until
   * [#184](https://github.com/sempods/sempods-kotlin/issues/184) removes the shape.
   */
  private fun registryRead(
    requestContext: ContainerRequestContext,
    format: RegistryFormat,
    model: Model,
    subject: IRI,
    legacy: () -> Any,
  ): Response {
    if (format == RegistryFormat.LEGACY_JSON) {
      return ContextRegistryResponses.deprecationHeaders(Response.ok(legacy()).type(format.contentType), format).build()
    }
    val tag = registryTag(model = model, format = format)
    evaluatePreconditions(requestContext, tag)?.let { return ContextRegistryResponses.deprecationHeaders(Response.fromResponse(it), format).build() }
    val entity = ContextRegistryResponses.entity(format, model, subject) { legacy() }
    return ContextRegistryResponses.deprecationHeaders(Response.ok(entity).type(format.contentType).tag(tag), format).build()
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

}

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
