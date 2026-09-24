package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.auth.core.OAuthSyntax
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.oauth.flows.PodServiceClientManagement
import org.sempods.pods.oauth.flows.PodServiceClientManagementRefusal
import org.sempods.pods.oauth.flows.PodServiceClientManagementResult
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import org.sempods.mcp.core.BearerChallenge

/**
 * [PodServiceClientManagement] on the wire. Every route takes a bearer carrying
 * [SERVICE_CLIENTS_MANAGE_SCOPE]. RFC 7592 is not adopted here; #124 records why.
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

  /** Takes the named scopes away and answers what the service holds afterwards. */
  @DELETE
  @Path("{clientId}/grants")
  fun removeGrants(
    @PathParam("pod") pod: String,
    @PathParam("clientId") clientId: String,
    @QueryParam("scope") scope: String?,
  ): Response = answer(pod, { p, caller -> management.removeGrants(p, caller, clientId, OAuthSyntax.parseScope(scope)) }) {
    noStore(Response.ok(describe(it)))
  }

  /** Removes the registration. */
  @DELETE
  @Path("{clientId}")
  fun revoke(@PathParam("pod") pod: String, @PathParam("clientId") clientId: String): Response =
    answer(pod, { p, caller -> management.revoke(p, caller, clientId) }) { Response.noContent().build() }

  /** Reads the pod and the bearer, runs [operation], and words a refusal; [done] renders the rest. */
  private inline fun <T> answer(
    pod: String,
    operation: (HostedPod, SempodsCredentials?) -> PodServiceClientManagementResult<T>,
    done: (T) -> Response,
  ): Response {
    val podDbo = fetchPodOrThrow(pod)
    return when (val result = operation(podDbo.hosted, resolveBearerOrNull(podDbo))) {
      is PodServiceClientManagementResult.Done -> done(result.value)
      is PodServiceClientManagementResult.Refused -> refused(podDbo.name, result.reason)
    }
  }

  private fun describe(registration: ServiceClientRegistration): Map<String, Any?> = linkedMapOf(
    "client_id" to registration.clientId,
    "client_name" to registration.label,
    "client_id_issued_at" to registration.createdAt.epochSecond,
    "last_used_at" to registration.lastUsedAt?.epochSecond,
    "scope" to registration.scopes.sorted().joinToString(" "),
    "origin" to if (registration.installed) "installed" else "provisioned",
  )

  /** Each refusal in words. The three about the bearer carry the pod's RFC 6750 challenge. */
  private fun refused(podName: String, reason: PodServiceClientManagementRefusal): Response = when (reason) {
    PodServiceClientManagementRefusal.SCOPE_REQUIRED ->
      challenged(403, podName, BearerChallenge.INSUFFICIENT_SCOPE, "this needs an authorization carrying '$SERVICE_CLIENTS_MANAGE_SCOPE'")
    PodServiceClientManagementRefusal.AUTHORITY_WITHDRAWN ->
      challenged(401, podName, BearerChallenge.INVALID_TOKEN, "this authorization no longer stands")
    PodServiceClientManagementRefusal.NOT_OWNER ->
      challenged(403, podName, BearerChallenge.INSUFFICIENT_SCOPE, "this pod's owner manages its service clients")
    PodServiceClientManagementRefusal.NOT_FOUND -> error(404, "no such service client on this pod")
    PodServiceClientManagementRefusal.PROVISIONED_BY_OPERATOR ->
      error(403, "this service client was provisioned by the host operator and is not changed here")
    PodServiceClientManagementRefusal.CONFLICT -> error(409, "the service client changed in between; read it again")
    PodServiceClientManagementRefusal.NO_SCOPE -> error(400, "name the scopes to remove")
  }

  private fun challenged(status: Int, podName: String, code: String, description: String): Response =
    Response.status(status)
      .header(HttpHeaders.WWW_AUTHENTICATE, buildBearerChallenge(podName, code))
      .entity(mapOf("error" to code, "error_description" to description))
      .type(MediaType.APPLICATION_JSON)
      .build()

  private fun error(status: Int, description: String): Response =
    Response.status(status).entity(mapOf("error_description" to description)).type(MediaType.APPLICATION_JSON).build()

  private fun noStore(builder: Response.ResponseBuilder): Response =
    builder.header(HttpHeaders.CACHE_CONTROL, "no-store").type(MediaType.APPLICATION_JSON).build()
}
