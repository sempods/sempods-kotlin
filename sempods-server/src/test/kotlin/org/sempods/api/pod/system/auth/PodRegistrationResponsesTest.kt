package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.Response
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pure unit — a registration answer's members, and the order a caller reads them in.
 *
 * RFC 7591 §3.2.1 lists the members, and a client reading the response as a stream sees them in
 * the order written here. What decides whether a value is there at all is
 * [PodClientRegistration][org.sempods.pods.oauth.flows.PodClientRegistration]'s.
 */
class PodRegistrationResponsesTest {

  @Test
  fun `a registered client is described in the order the RFC lists`() {
    val body = body(client(clientName = "Notes", clientUri = "https://app.example", contacts = listOf("a@b.example")))

    assertEquals(
      listOf(
        "client_id", "redirect_uris", "token_endpoint_auth_method", "grant_types", "response_types",
        "client_name", "client_uri", "contacts",
      ),
      body.keys.toList(),
    )
    assertEquals("dyn:abc", body["client_id"])
    assertEquals(listOf("https://app.example/cb"), body["redirect_uris"])
    assertEquals("none", body["token_endpoint_auth_method"], "these clients hold no secret")
    assertEquals(listOf("authorization_code", "refresh_token"), body["grant_types"])
    assertEquals(listOf("code"), body["response_types"])
  }

  @Test
  fun `a member the client did not name is absent, and an empty contacts list is too`() {
    // `"contacts": []` would claim the client named no way to reach it, which is a different
    // statement from saying nothing about it.
    val body = body(client())

    assertEquals(
      listOf("client_id", "redirect_uris", "token_endpoint_auth_method", "grant_types", "response_types"),
      body.keys.toList(),
    )
    for (absent in listOf("client_name", "client_uri", "logo_uri", "software_id", "software_version",
                          "contacts", "tos_uri", "policy_uri")) {
      assertFalse(absent in body, "$absent reached the answer")
    }
  }

  @Test
  fun `a refusal carries the RFC's own code and the sentence beside it`() {
    val response = PodRegistrationResponses.render(
      PodRegistrationResult.Refused(PodRegistrationError.INVALID_CLIENT_METADATA, "logo_uri must be https"),
    )

    assertEquals(400, response.status)
    assertEquals(
      mapOf("error" to "invalid_client_metadata", "error_description" to "logo_uri must be https"),
      response.entity,
    )
  }

  private fun client(
    clientName: String? = null,
    clientUri: String? = null,
    contacts: List<String> = emptyList(),
  ) = PodRegistrationResult.Registered(
    clientId = "dyn:abc",
    redirectUris = setOf("https://app.example/cb"),
    clientName = clientName,
    clientUri = clientUri,
    logoUri = null,
    softwareId = null,
    softwareVersion = null,
    contacts = contacts,
    tosUri = null,
    policyUri = null,
  )

  @Suppress("UNCHECKED_CAST")
  private fun body(registered: PodRegistrationResult.Registered): Map<String, Any?> {
    val response: Response = PodRegistrationResponses.render(registered)
    assertEquals(201, response.status)
    return response.entity as Map<String, Any?>
  }
}
