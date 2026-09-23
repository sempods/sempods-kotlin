package org.sempods.api.pod.system.auth

import com.fasterxml.jackson.core.type.TypeReference
import jakarta.ws.rs.core.Response
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pure unit — a registration answer's members.
 *
 * RFC 7591 §3.2.1 lists them; what decides whether a value is there at all is
 * [PodClientRegistration][org.sempods.pods.oauth.flows.PodClientRegistration]'s. Order is not
 * asserted: the SDK writes a JSON object, and a caller reads members by name.
 */
class PodRegistrationResponsesTest {

  @Test
  fun `a registered client is described the way the RFC names it`() {
    val body = body(client(clientName = "Notes", clientUri = "https://app.example", contacts = listOf("a@b.example")))

    assertEquals("dyn:abc", body["client_id"])
    assertEquals(listOf("https://app.example/cb"), body["redirect_uris"])
    assertEquals("none", body["token_endpoint_auth_method"], "these clients hold no secret")
    assertEquals(listOf("authorization_code", "refresh_token"), body["grant_types"])
    assertEquals(listOf("code"), body["response_types"])
    assertEquals("Notes", body["client_name"])
    assertEquals("https://app.example", body["client_uri"])
    assertEquals(listOf("a@b.example"), body["contacts"])
  }

  @Test
  fun `a member the client did not name is absent, and an empty contacts list is too`() {
    // `"contacts": []` would claim the client named no way to reach it, which is a different
    // statement from saying nothing about it.
    val body = body(client())

    for (absent in listOf("client_name", "client_uri", "logo_uri", "software_id", "software_version",
                          "contacts", "tos_uri", "policy_uri", "client_secret", "client_secret_expires_at")) {
      assertFalse(absent in body, "$absent reached the answer")
    }
  }

  @Test
  fun `the answer is not cached`() {
    // The answer carries a client identity, and a shared cache holding one hands it to whoever
    // asks next.
    val response = PodRegistrationResponses.render(client())

    assertEquals("no-store", response.getHeaderString("Cache-Control"))
  }

  @Test
  fun `a refusal carries the RFC's own code and the sentence beside it`() {
    val response = PodRegistrationResponses.refused(
      PodRegistrationError.INVALID_CLIENT_METADATA,
      "logo_uri must be https",
    )

    assertEquals(400, response.status)
    assertEquals("invalid_client_metadata", json(response)["error"])
    assertEquals("logo_uri must be https", json(response)["error_description"])
  }

  @Test
  fun `a refusal naming the caller's own value carries no character the field forbids`() {
    // RFC 6749 §5.2 excludes `"` and `\` from `error_description`, and the value a refusal names
    // came from whoever sent it.
    val response = PodRegistrationResponses.refused(
      PodRegistrationError.INVALID_REDIRECT_URI,
      """redirect_uri must be https: "ftp://a\b"""",
    )

    assertEquals("redirect_uri must be https: ftp://ab", json(response)["error_description"])
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

  private fun body(registered: PodRegistrationResult.Registered): Map<String, Any?> {
    val response = PodRegistrationResponses.render(registered)
    assertEquals(201, response.status)
    return json(response)
  }

  private fun json(response: Response): Map<String, Any?> =
    JsonMappers.default().readValue(response.entity as String, object : TypeReference<Map<String, Any?>>() {})
}
