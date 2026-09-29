package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.flows.PodServiceClientManagement
import org.sempods.pods.oauth.flows.PodServiceClientManagementRefusal
import org.sempods.pods.oauth.flows.PodServiceClientManagementResult
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration

/**
 * [PodServiceClientManagement] on the wire. Every route takes a bearer carrying
 * [SERVICE_CLIENTS_MANAGE_SCOPE]. RFC 7592 is not adopted here; #124 records why. A registration is
 * made at `POST {pod}/_system/auth/register`, with the same bearer ([PodAuthEndpoint]).
 */
@Path("{pod}/_system/auth/service-clients")
@Produces(MediaType.APPLICATION_JSON)
class PodServiceClientsEndpoint @Inject constructor(
  private val management: PodServiceClientManagement,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(podFacade = podFacade, podDao = podDao) {

  /** Every registration on the pod, with when it was last used. Never a secret. */
  @GET
  fun list(@PathParam("pod") pod: String): Response = answer(pod, { p, caller -> management.list(p, caller) }) {
    noStore(Response.ok(mapOf("serviceClients" to it.map(::describe))))
  }

  /** A new secret, answered once. */
  @POST
  @Path("{clientId}/secret")
  fun rotate(@PathParam("pod") pod: String, @PathParam("clientId") clientId: String): Response =
    answer(pod, { p, caller -> management.rotate(p, caller, clientId) }) {
      noStore(Response.ok(mapOf("client_id" to it.registration.clientId, "client_secret" to it.secret)))
    }

  /** One registration, with its grants version as a strong `ETag` for [replaceGrants]. */
  @GET
  @Path("{clientId}")
  fun get(@PathParam("pod") pod: String, @PathParam("clientId") clientId: String): Response =
    answer(pod, { p, caller -> management.get(p, caller, clientId) }, ::described)

  /**
   * Makes the JSON array of scopes in [body] the service's grants, at the version `If-Match` names.
   * `[]` removes every grant and keeps the registration.
   *
   * `If-Match` is one strong entity tag, the `ETag` a read answered: without it `428`, so two tools
   * never overwrite each other unseen. `*` or a list is `400`: either would let the replace land on
   * grants the caller never read.
   */
  @PUT
  @Path("{clientId}/grants")
  @Consumes(MediaType.APPLICATION_JSON)
  fun replaceGrants(
    @PathParam("pod") pod: String,
    @PathParam("clientId") clientId: String,
    @HeaderParam(HttpHeaders.IF_MATCH) ifMatch: String?,
    body: String?,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    val caller = requireBearerOrThrow(podDbo)
    ifMatch ?: return error(428, "send If-Match with the ETag a read of this service client answered")
    val expectedVersion = grantsVersionOf(ifMatch) ?: return error(400, "If-Match is the one ETag a read of this service client answered")
    val scopes = scopesOf(body) ?: return error(400, "the body is a JSON array of scope strings")
    return answer(podDbo, management.replaceGrants(podDbo.hosted, caller, clientId, expectedVersion, scopes), ::described)
  }

  /** Removes the registration. */
  @DELETE
  @Path("{clientId}")
  fun revoke(@PathParam("pod") pod: String, @PathParam("clientId") clientId: String): Response =
    answer(pod, { p, caller -> management.revoke(p, caller, clientId) }) { Response.noContent().build() }

  /** Reads the pod and the bearer, runs [operation], and words a refusal; [done] renders the rest. */
  private inline fun <T> answer(
    pod: String,
    operation: (HostedPod, SempodsCredentials) -> PodServiceClientManagementResult<T>,
    done: (T) -> Response,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    return answer(podDbo, operation(podDbo.hosted, requireBearerOrThrow(podDbo)), done)
  }

  private inline fun <T> answer(podDbo: PodDbo, result: PodServiceClientManagementResult<T>, done: (T) -> Response): Response =
    when (result) {
      is PodServiceClientManagementResult.Done -> done(result.value)
      is PodServiceClientManagementResult.Refused -> refused(result.reason, result.detail)
      is PodServiceClientManagementResult.Unauthorized ->
        ownerAuthorityRefused(podDbo.name, result.reason, SERVICE_CLIENTS_MANAGE_SCOPE, manages = "service clients")
    }

  /** A registration, with its grants version as the `ETag` a replace names in `If-Match`. */
  private fun described(registration: ServiceClientRegistration): Response =
    noStore(Response.ok(describe(registration)).tag(EntityTag(registration.grantsVersion.toString())))

  private fun describe(registration: ServiceClientRegistration): Map<String, Any?> = linkedMapOf(
    "client_id" to registration.clientId,
    "client_name" to registration.label,
    "client_id_issued_at" to registration.createdAt.epochSecond,
    "last_used_at" to registration.lastUsedAt?.epochSecond,
    "scope" to registration.scopes.sorted().joinToString(" "),
    "grants_version" to registration.grantsVersion,
    "origin" to if (registration.registered) "registered" else "provisioned",
  ).apply {
    // Only while the registration waits for the owner's consent; it is gone after the deadline.
    registration.pendingUntil?.let { put(PodRegistrationResponses.ACTIVATION_EXPIRES_AT, it.epochSecond) }
  }

  /** Each refusal in words. */
  private fun refused(reason: PodServiceClientManagementRefusal, detail: String?): Response = when (reason) {
    PodServiceClientManagementRefusal.NOT_FOUND -> error(404, "no such service client on this pod")
    PodServiceClientManagementRefusal.PROVISIONED_BY_OPERATOR ->
      error(403, "this service client was provisioned by the host operator, who holds its secret and its registration")
    PodServiceClientManagementRefusal.CONFLICT -> error(409, "the service client changed in between; read it again")
    PodServiceClientManagementRefusal.VERSION_MISMATCH ->
      error(412, "the grants changed since the version If-Match names; read them again")
    PodServiceClientManagementRefusal.UNGRANTABLE -> error(400, "a service client cannot be given ${detail ?: "these scopes"}")
  }

  /** The version in an `If-Match` of one strong tag; `null` for anything else. */
  private fun grantsVersionOf(ifMatch: String): Long? =
    GRANTS_VERSION_TAG.matchEntire(ifMatch.trim())?.groupValues?.get(1)?.toLongOrNull()

  /** The scopes in a JSON array of strings; `null` for any other body. */
  private fun scopesOf(body: String?): Set<String>? {
    val raw = body?.takeIf { it.isNotBlank() } ?: return null
    val node = try {
      JsonMappers.strict().readTree(raw)
    } catch (_: Exception) {
      return null
    }
    val elements = node.takeIf { it.isArray }?.values() ?: return null
    return if (elements.all { it.isString }) elements.mapTo(linkedSetOf()) { it.stringValue() } else null
  }

  private fun error(status: Int, description: String): Response =
    Response.status(status).entity(mapOf("error_description" to description)).type(MediaType.APPLICATION_JSON).build()

  private fun noStore(builder: Response.ResponseBuilder): Response =
    builder.header(HttpHeaders.CACHE_CONTROL, "no-store").type(MediaType.APPLICATION_JSON).build()

  private companion object {
    /** A strong entity tag holding a grants version: `"3"`. */
    val GRANTS_VERSION_TAG = Regex(""""(\d{1,18})"""")
  }
}
