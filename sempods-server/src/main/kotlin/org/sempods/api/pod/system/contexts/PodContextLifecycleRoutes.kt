package org.sempods.api.pod.system.contexts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.eclipse.rdf4j.model.util.Values
import org.sempods.ExperimentalSempodsServerApi
import org.sempods.SempodsConfig
import org.sempods.api.PodBearerRoutes
import org.sempods.api.pod.resources.PodContextWriteAuthorizer
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.persist.PodContextDbo
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.flows.PodOwnerAuthority
import org.sempods.pods.oauth.flows.PodOwnerAuthorityCheck
import java.net.URI

/**
 * What the context lifecycle routes need from this server: who may create or delete a context, the
 * creation and the cascade, and the registry answers. The routes themselves are the spec's optional
 * `context-management` module and live in `:sempods-context-management`.
 *
 * **Why the decisions stay here.** Who may manage a context is this implementation's grant model —
 * the owner's [CONTEXTS_MANAGE_SCOPE] authority or a covering `#manage` grant — and deleting one
 * cascades into grants, refresh tokens, media assignments and the store ([PodFacade.removeContext]).
 * Both hold whether a route asks or not; the consent dialog creates contexts too. What the module
 * owns is the wire surface and the naming rules on creation.
 *
 * Every refusal is thrown as a [WebApplicationException] carrying its answer, as the server's other
 * route helpers do ([PodBearerRoutes]).
 */
@ExperimentalSempodsServerApi
class PodContextLifecycleRoutes @Inject internal constructor(
  private val podFacade: PodFacade,
  private val contextWriteAuthorizer: PodContextWriteAuthorizer,
  private val ownerAuthority: PodOwnerAuthority,
  private val bearerRoutes: PodBearerRoutes,
  private val config: SempodsConfig,
) {

  /**
   * `406` where the caller accepts no registry representation, else `null`. Asked first, so no
   * context comes into existence for an answer nobody can read (`SPS-CTX-037`).
   */
  fun refuseUnacceptable(accept: String?): Response? =
    if (ContextRegistryNegotiation.select(accept) == null) ContextRegistryResponses.notAcceptable() else null

  /**
   * The context IRI [contextPath] names on [pod]: `404` for an unknown pod, then `400` for a path
   * that cannot be one — both before the caller is authenticated, as the routes always answered.
   */
  fun contextUri(pod: String, contextPath: String): URI {
    bearerRoutes.fetchPodOrThrow(pod)
    return ContextRegistryResponses.contextUri(podBaseUrl(pod), contextPath)
  }

  /**
   * Authorizes creating or deleting [contextUri] and returns the subject to record as its creator.
   *
   * Two allow paths, both read off the same pod access token:
   * 1. The owner's own authority, through a bearer carrying [CONTEXTS_MANAGE_SCOPE] whose recorded
   *    authority still stands and names the pod's current owner ([PodOwnerAuthority]). It reaches
   *    every context, registered or not, and needs nothing granted — which is what lets a fresh pod
   *    get its first context from a program (`SPS-CTX-019`).
   * 2. A `<root>#manage` grant covering [contextUri] via the slash-delimited rule shared with the
   *    write enforcer ([PodContextWriteAuthorizer.isCoveredByManageScope]) — a service client, or
   *    an app the owner or another person delegated it to.
   *
   * **Ownership alone is neither.** A bearer whose `sub` owns the pod is an application holding
   * what was approved for it (`SPS-GRANT-011`, `SPS-GRANT-013`); an app approved for one context
   * must not delete the rest because the person behind its token could.
   *
   * [credentials] are authenticated already. A withdrawn [CONTEXTS_MANAGE_SCOPE] authority is
   * `401`; a caller holding neither path `403`, whether the context exists or not (`SPS-CORE-018`).
   */
  fun authorize(pod: String, contextUri: URI, credentials: SempodsCredentials): String {
    val podDbo = bearerRoutes.fetchPodOrThrow(pod)
    if (CONTEXTS_MANAGE_SCOPE in credentials.oauthScopes) {
      when (val check = ownerAuthority.check(bearerRoutes.hosted(podDbo), credentials, CONTEXTS_MANAGE_SCOPE)) {
        is PodOwnerAuthorityCheck.Standing -> return check.authority.webId
        is PodOwnerAuthorityCheck.Refused ->
          throw WebApplicationException(bearerRoutes.ownerAuthorityRefused(pod, check.reason, CONTEXTS_MANAGE_SCOPE, manages = "contexts"))
      }
    }

    if (contextWriteAuthorizer.isCoveredByManageScope(credentials, contextUri)) {
      return credentials.tokenSub ?: credentials.oauthClientId ?: throw WebApplicationException(
        Response.status(403).entity("this caller carries no identity to record as the context's creator").type("text/plain").build()
      )
    }

    throw WebApplicationException(
      Response.status(403).entity("missing manage permission for context '$contextUri'").type("text/plain").build()
    )
  }

  /**
   * Creates [contextUri] on [pod], or finds it existing: `201` or `200` with its registry
   * description in the representation [accept] selects (`SPS-CTX-015`, `SPS-CTX-016`, `SPS-CTX-037`).
   * An existing context is a no-op: field-level updates are out of scope here.
   */
  fun create(
    pod: String,
    contextUri: URI,
    label: String?,
    description: String?,
    public: Boolean,
    createdBy: String,
    accept: String?,
  ): Response {
    val format = ContextRegistryNegotiation.select(accept) ?: return ContextRegistryResponses.notAcceptable()
    podFacade.findContext(pod, contextUri)?.let { return description(status = 200, row = it, format = format, pod = pod) }

    val created = podFacade.registerContext(
      podName = pod,
      contextUri = contextUri,
      label = label?.trim()?.ifBlank { null },
      description = description?.trim()?.ifBlank { null },
      public = public,
      createdBy = createdBy,
    )
    if (created != null) return description(status = 201, row = created, format = format, pod = pod)
    // Another caller created the same row between the lookup and the insert. PUT is idempotent and
    // the caller's post-condition holds, so this is the same 200 an existing row answers.
    val won = podFacade.findContext(pod, contextUri)
      ?: throw WebApplicationException(Response.status(500).entity("unexpected create failure for $contextUri").type("text/plain").build())
    return description(status = 200, row = won, format = format, pod = pod)
  }

  /**
   * Deletes [contextUri] with everything that hangs on it ([PodFacade.removeContext]): `204`, or `404`
   * where no such context is registered.
   */
  fun remove(pod: String, contextUri: URI): Response {
    podFacade.findContext(pod, contextUri) ?: throw ContextRegistryResponses.unknownContext()
    podFacade.removeContext(podName = pod, context = contextUri)
    return Response.noContent().build()
  }

  private fun podBaseUrl(pod: String): String = "${config.apiBaseUrl}${pod}/"

  /** A created or existing context, as its registry description (`SPS-CTX-037`). */
  private fun description(status: Int, row: PodContextDbo, format: RegistryFormat, pod: String): Response {
    val model = PodContextRegistryRdf.describe(row = row, podBaseUrl = podBaseUrl(pod))
    val builder = Response.status(status)
      .entity(ContextRegistryResponses.entity(format, model, Values.iri(row.contextUri)) { row.toPutResponse() })
      .type(format.contentType)
    // No `ETag` here: `SPS-CTX-037` gives a write no validator, and a tag would invite an `If-Match`
    // this route does not evaluate.
    return ContextRegistryResponses.deprecationHeaders(builder, format).build()
  }

  private fun PodContextDbo.toPutResponse(): PutPodContextResponse = PutPodContextResponse(
    contextIri = contextUri,
    label = label,
    description = description,
    public = isPublic,
    createdAt = createdAt.toString(),
  )
}

/**
 * The transitional `application/json` answer to a context `PUT`: the created or existing context's
 * metadata. Goes with [#184](https://github.com/sempods/sempods-kotlin/issues/184) like the
 * listing's envelopes.
 */
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
