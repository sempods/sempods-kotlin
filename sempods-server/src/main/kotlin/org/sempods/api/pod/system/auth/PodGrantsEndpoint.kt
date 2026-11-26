package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.PodBaseEndpoint
import org.sempods.api.PodBearerRoutes
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.AppHolder
import org.sempods.pods.grants.GRANTS_MANAGE_SCOPE
import org.sempods.pods.grants.PodContextPermissionResolver
import org.sempods.pods.grants.ReachingGrant
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.flows.PodGrantManagement
import org.sempods.pods.oauth.flows.PodGrantManagementRefusal
import org.sempods.pods.oauth.flows.PodGrantManagementResult
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [PodGrantManagement] on the wire: who reaches each context, and the withdrawal of one direct
 * grant. Every route takes a bearer carrying [GRANTS_MANAGE_SCOPE]. Experimental and specific to
 * this implementation; what each route answers is `sempods-server/docs/auth/service-clients.md`
 * §"Managing grants".
 *
 * Context IRIs, WebIDs and client identifiers are path segments in base64url without padding, as
 * on `_system/resources`: a WebID's slashes would not survive percent-encoding in a segment.
 */
@Path("{pod}/_system/auth/grants")
@Produces(MediaType.APPLICATION_JSON)
class PodGrantsEndpoint @Inject constructor(
  private val management: PodGrantManagement,
  private val bearerRoutes: PodBearerRoutes,
  private val resolver: PodContextPermissionResolver,
) : PodBaseEndpoint() {

  /** How many people, owner's apps and services reach each registered context. */
  @GET
  fun counts(@PathParam("pod") pod: String, @Context requestContext: ContainerRequestContext): Response =
    answer(pod, requestContext, { p, caller -> management.counts(p, caller) }) { counts ->
      ok(mapOf("contexts" to counts.map {
        linkedMapOf("context_iri" to it.contextUri, "people" to it.people, "apps" to it.ownerApps, "services" to it.services)
      }))
    }

  /** Who reaches one context, grouped. */
  @GET
  @Path("{context}")
  fun holders(
    @PathParam("pod") pod: String,
    @PathParam("context") context: String,
    @Context requestContext: ContainerRequestContext,
  ): Response = answer(pod, requestContext, { p, caller -> management.holders(p, caller, decode(context, "context")) }) { holders ->
    ok(linkedMapOf(
      "context_iri" to holders.contextUri,
      "people" to holders.people.map { person ->
        linkedMapOf(
          "web_id" to person.webId,
          "permissions" to permissions(person.grants),
          "grants" to grants(person.grants),
          "delegations" to person.delegations.map(::app),
        )
      },
      "apps" to holders.ownerApps.map(::app),
      "services" to holders.services.map { service ->
        linkedMapOf<String, Any?>("client_id" to service.clientId).apply {
          service.name?.let { put("client_name", it) }
          put("permissions", permissions(service.grants))
          put("grants", grants(service.grants))
          service.lastUsedAt?.let { put("last_used_at", iso(it)) }
          put("grants_version", service.grantsVersion)
        }
      },
    ))
  }

  /** Withdraws a person's direct grants on the context, with the apps acting for them there. */
  @DELETE
  @Path("{context}/people/{webId}")
  fun withdrawPerson(
    @PathParam("pod") pod: String,
    @PathParam("context") context: String,
    @PathParam("webId") webId: String,
    @Context requestContext: ContainerRequestContext,
  ): Response = answer(pod, requestContext, { p, caller ->
    management.withdrawPerson(p, caller, decode(context, "context"), decode(webId, "WebID"))
  }) { Response.noContent().build() }

  /** Withdraws one of the owner's own delegations on the context. */
  @DELETE
  @Path("{context}/apps/{clientId}")
  fun withdrawApp(
    @PathParam("pod") pod: String,
    @PathParam("context") context: String,
    @PathParam("clientId") clientId: String,
    @Context requestContext: ContainerRequestContext,
  ): Response = answer(pod, requestContext, { p, caller ->
    management.withdrawOwnerApp(p, caller, decode(context, "context"), decode(clientId, "client_id"))
  }) { Response.noContent().build() }

  /**
   * Withdraws a service's direct grants on the context, at the grants version `If-Match` names —
   * the `grants_version` of the listing, or the `ETag` of `GET …/service-clients/{clientId}`.
   */
  @DELETE
  @Path("{context}/services/{clientId}")
  fun withdrawService(
    @PathParam("pod") pod: String,
    @PathParam("context") context: String,
    @PathParam("clientId") clientId: String,
    @HeaderParam(HttpHeaders.IF_MATCH) ifMatch: String?,
    @Context requestContext: ContainerRequestContext,
  ): Response {
    val podDbo = bearerRoutes.fetchPodOrThrow(pod)
    val caller = bearerRoutes.requireBearerOrThrow(podDbo, requestContext)
    ifMatch ?: return error(428, "send If-Match with the grants_version the listing answered, as a strong ETag")
    val expectedVersion = grantsVersionOf(ifMatch)
      ?: return error(400, "If-Match is one strong ETag holding the grants_version the listing answered")
    val result = management.withdrawService(
      bearerRoutes.hosted(podDbo), caller, decode(context, "context"), decode(clientId, "client_id"), expectedVersion,
    )
    return answer(podDbo, result) { Response.noContent().build() }
  }

  /** Reads the pod and the bearer, runs [operation], and words a refusal; [done] renders the rest. */
  private inline fun <T> answer(
    pod: String,
    requestContext: ContainerRequestContext,
    operation: (HostedPod, SempodsCredentials) -> PodGrantManagementResult<T>,
    done: (T) -> Response,
  ): Response {
    val podDbo = bearerRoutes.fetchPodOrThrow(pod)
    val caller = bearerRoutes.requireBearerOrThrow(podDbo, requestContext)
    return answer(podDbo, operation(bearerRoutes.hosted(podDbo), caller), done)
  }

  private inline fun <T> answer(podDbo: PodDbo, result: PodGrantManagementResult<T>, done: (T) -> Response): Response =
    when (result) {
      is PodGrantManagementResult.Done -> done(result.value)
      is PodGrantManagementResult.Refused -> refused(result)
      is PodGrantManagementResult.Unauthorized ->
        bearerRoutes.ownerAuthorityRefused(podDbo.name, result.reason, GRANTS_MANAGE_SCOPE, manages = "grants")
    }

  private fun refused(refusal: PodGrantManagementResult.Refused): Response = when (refusal.reason) {
    PodGrantManagementRefusal.UNKNOWN_CONTEXT -> error(404, "no context is registered at this IRI on this pod")
    PodGrantManagementRefusal.NOT_HELD -> error(404, "this holder holds no grant reaching this context")
    PodGrantManagementRefusal.INHERITED -> Response.status(409)
      .entity(linkedMapOf(
        "error_description" to "this grant reaches the context from an ancestor's #manage grant; withdraw it there",
        "inherited_from" to refusal.inheritedFrom,
      ))
      .type(MediaType.APPLICATION_JSON)
      .build()
    PodGrantManagementRefusal.VERSION_MISMATCH -> error(412, "the grants changed since the version If-Match names; read them again")
  }

  private fun app(app: AppHolder): Map<String, Any?> = linkedMapOf<String, Any?>("client_id" to app.clientId).apply {
    app.name?.let { put("client_name", it) }
    put("permissions", permissions(app.grants))
    put("grants", grants(app.grants))
    app.lastUsedAt?.let { put("last_used_at", iso(it)) }
  }

  /** What the holder can do on the context, in the form the context catalogue reports it. */
  private fun permissions(grants: List<ReachingGrant>): List<String> =
    if (grants.isEmpty()) emptyList() else resolver.collapse(grants.mapTo(mutableSetOf()) { it.permission })

  private fun grants(grants: List<ReachingGrant>): List<Map<String, Any?>> =
    grants.map { linkedMapOf("permission" to it.permission.value, "inherited_from" to it.inheritedFrom) }

  /** RFC 3339 in UTC, to the second: what a console shows, without the store's milliseconds. */
  private fun iso(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

  /** A base64url segment as the string it encodes, or `400`. */
  private fun decode(segment: String, role: String): String = try {
    UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(segment).toString()
  } catch (e: IllegalArgumentException) {
    throw WebApplicationException(error(400, "the $role segment is not base64url: ${e.message}"))
  }

  /** The version in an `If-Match` of one strong tag; `null` for anything else. */
  private fun grantsVersionOf(ifMatch: String): Long? =
    GRANTS_VERSION_TAG.matchEntire(ifMatch.trim())?.groupValues?.get(1)?.toLongOrNull()

  private fun ok(body: Any): Response =
    Response.ok(body).header(HttpHeaders.CACHE_CONTROL, "no-store").type(MediaType.APPLICATION_JSON).build()

  private fun error(status: Int, description: String): Response =
    Response.status(status).entity(mapOf("error_description" to description)).type(MediaType.APPLICATION_JSON).build()

  private companion object {
    /** A strong entity tag holding a grants version: `"3"`. */
    val GRANTS_VERSION_TAG = Regex(""""(\d{1,18})"""")
  }
}
