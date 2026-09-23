package org.sempods.api.pod.system.auth

import com.fasterxml.jackson.core.type.TypeReference
import jakarta.ws.rs.core.Response
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationRefusal
import org.sempods.pods.oauth.flows.PodRegistrationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    val response = PodRegistrationResponses.render(REALM, client())

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

  @Test
  fun `a service client is described with its secret, and a secret that does not expire`() {
    // RFC 7591 §3.2.1 makes `client_secret_expires_at` required whenever a secret is returned, and
    // `0` is its spelling for one that never expires. `client_id_issued_at` beside it is what the
    // caller opens the grant consent with.
    val issuedAt = Instant.parse("2026-09-23T10:15:30Z")
    val response = PodRegistrationResponses.render(
      REALM,
      PodRegistrationResult.ServiceRegistered(
        clientId = "svc:opaque",
        clientName = "Notes Sync",
        issuedAt = issuedAt,
        secret = "sc_the-secret",
      ),
    )

    assertEquals(201, response.status)
    val body = json(response)
    assertEquals("svc:opaque", body["client_id"])
    assertEquals("sc_the-secret", body["client_secret"])
    assertEquals(0, body["client_secret_expires_at"])
    assertEquals(issuedAt.epochSecond.toInt(), body["client_id_issued_at"])
    assertEquals("Notes Sync", body["client_name"])
    assertEquals("client_secret_basic", body["token_endpoint_auth_method"])
    assertEquals(listOf("client_credentials"), body["grant_types"])
    assertEquals("no-store", response.getHeaderString("Cache-Control"))
  }

  @Test
  fun `a refusal about the caller's own bearer is a challenge, and carries no body`() {
    // RFC 6750 §3 puts the error in `WWW-Authenticate`. A spent installation authority is a 401,
    // because the way out is a new authorization; a bearer that simply does not cover this is a
    // 403, because there is nothing to go and get.
    val spent = PodRegistrationResponses.render(
      REALM,
      PodRegistrationResult.Unauthorized(PodRegistrationRefusal.AUTHORITY_SPENT, "already registered"),
    )
    assertEquals(401, spent.status)
    assertNull(spent.entity)
    val challenge = assertNotNull(spent.getHeaderString("WWW-Authenticate"))
    assertTrue("""realm="$REALM"""" in challenge, challenge)
    assertTrue("""error="invalid_token"""" in challenge, challenge)

    val unscoped = PodRegistrationResponses.render(
      REALM,
      PodRegistrationResult.Unauthorized(PodRegistrationRefusal.NOT_AUTHORIZED, "not the owner"),
    )
    assertEquals(403, unscoped.status)
    assertTrue("""error="insufficient_scope"""" in assertNotNull(unscoped.getHeaderString("WWW-Authenticate")))
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
    val response = PodRegistrationResponses.render(REALM, registered)
    assertEquals(201, response.status)
    return json(response)
  }

  private fun json(response: Response): Map<String, Any?> =
    JsonMappers.default().readValue(response.entity as String, object : TypeReference<Map<String, Any?>>() {})

  private companion object {
    private const val REALM = "alice"
  }
}
