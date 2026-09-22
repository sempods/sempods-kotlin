package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.pods.oauth.flows.PodRegistrationResult

/**
 * A registration answer on the wire (RFC 7591 §§3.2.1–3.2.2).
 *
 * [PodClientRegistration][org.sempods.pods.oauth.flows.PodClientRegistration] decides what the
 * client is; this decides how that reaches the caller. Both shapes are handed to Jersey as a map
 * and serialized from there.
 */
internal object PodRegistrationResponses {

  fun render(result: PodRegistrationResult): Response = when (result) {
    is PodRegistrationResult.Registered -> json(201, body(result))
    is PodRegistrationResult.Refused ->
      json(400, mapOf("error" to result.error.code, "error_description" to result.description))
  }

  /**
   * The registered client, in the order RFC 7591 §3.2.1 lists it.
   *
   * A `LinkedHashMap` because that order is what a caller reads, and two rules decide what is in
   * it: a `null` is absent, and an empty `contacts` is absent too — `[]` would claim the client
   * named no way to reach it, which is a different statement from saying nothing.
   */
  private fun body(client: PodRegistrationResult.Registered): Map<String, Any?> {
    val body = linkedMapOf<String, Any?>(
      "client_id" to client.clientId,
      "redirect_uris" to client.redirectUris.toList(),
      "token_endpoint_auth_method" to "none",
      "grant_types" to listOf("authorization_code", "refresh_token"),
      "response_types" to listOf("code"),
    )
    client.clientName?.let { body["client_name"] = it }
    client.clientUri?.let { body["client_uri"] = it }
    client.logoUri?.let { body["logo_uri"] = it }
    client.softwareId?.let { body["software_id"] = it }
    client.softwareVersion?.let { body["software_version"] = it }
    if (client.contacts.isNotEmpty()) body["contacts"] = client.contacts
    client.tosUri?.let { body["tos_uri"] = it }
    client.policyUri?.let { body["policy_uri"] = it }
    return body
  }

  private fun json(status: Int, body: Map<String, Any?>): Response =
    Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build()
}
