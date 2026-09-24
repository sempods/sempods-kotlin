package org.sempods.client

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import tools.jackson.databind.json.JsonMapper

/** What each service-client operation puts on the wire, and what it reads back. */
class SempodsPodServiceClientsContractTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val json = JsonMapper()

  private val alice get() = SempodsPodBase.of("$origin/alice")

  private fun serviceClients() = SempodsPodServiceClients(SempodsSession(alice, SempodsRequestAuth.bearer("tok-1")), client)

  private fun answer(path: String, status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(path)).respond(response)
  }

  private val registration =
    """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":1700000000,"client_secret_expires_at":0,""" +
      """"client_name":"Notes Sync","grant_types":["client_credentials"],"token_endpoint_auth_method":"client_secret_basic"}"""

  private val described =
    """{"client_id":"svc:1","client_name":"Notes Sync","client_id_issued_at":1700000000,"last_used_at":1700000600,""" +
      """"scope":"urn:a#read urn:b#write","origin":"installed"}"""

  @Test
  fun `an installation registers the confidential shape with the installer's bearer, and reads the secret once`() {
    answer("/alice/_system/auth/register", 201, registration, "Cache-Control" to "no-store")

    val registered = checkNotNull(serviceClients().register("Notes Sync").body)

    assertEquals("svc:1", registered.clientId)
    assertEquals("sc_secret", registered.clientSecret)
    assertEquals("Notes Sync", registered.clientName)
    assertEquals(Instant.ofEpochSecond(1700000000), registered.issuedAt)
    assertNull(registered.secretExpiresAt, "client_secret_expires_at 0 is a secret that does not expire")
    assertFalse(registered.toString().contains("sc_secret"), registered.toString())
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("POST", sent.method.value)
    assertEquals("Bearer tok-1", sent.getFirstHeader("Authorization"))
    assertEquals(
      json.readTree("""{"client_name":"Notes Sync","grant_types":["client_credentials"],"token_endpoint_auth_method":"client_secret_basic"}"""),
      json.readTree(String(sent.body.rawBytes, Charsets.UTF_8)),
    )
  }

  @Test
  fun `a secret with an expiry reads as that instant`() {
    answer("/alice/_system/auth/register", 201, registration.replace(""""client_secret_expires_at":0""", """"client_secret_expires_at":1800000000"""))

    assertEquals(Instant.ofEpochSecond(1800000000), serviceClients().register("Notes Sync").body?.secretExpiresAt)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"client_id":"svc:1","client_id_issued_at":1700000000}""",
      """{"client_id":"svc:1","client_secret":"sc_secret"}""",
      """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":"1700000000","client_secret_expires_at":0}""",
      """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":1700000000}""",
      """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":9223372036854775807,"client_secret_expires_at":0}""",
      """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":1700000000,"client_secret_expires_at":-9223372036854775808}""",
    ],
  )
  fun `a registration answer without the secret, a time or the secret's expiry is a decoding failure that quotes nothing`(body: String) {
    answer("/alice/_system/auth/register", 201, body)

    val failure = assertThrows<SempodsDecodingException> { serviceClients().register("Notes Sync") }

    assertFalse(failure.message!!.contains("sc_secret"), failure.message)
    assertEquals(body, serviceClients().registerJson("Notes Sync").body)
  }

  @Test
  fun `a spent installer token is the pod's challenge, kept on the failure`() {
    answer("/alice/_system/auth/register", 401, "this authorization no longer stands", "WWW-Authenticate" to """Bearer error="invalid_token"""")

    val failure = assertThrows<SempodsStatusException> { serviceClients().register("Notes Sync") }

    assertEquals(401, failure.status)
    assertEquals("""Bearer error="invalid_token"""", failure.headers["WWW-Authenticate"])
  }

  @Test
  fun `the grant consent URL is the pod's own and names the caller, the service and the rows`() {
    val url = serviceClients().grantConsentUrl("dyn:abc", "http://127.0.0.1:4711/cb", "g1", "svc:1", listOf("urn:a#read", "urn:b#write"))

    assertEquals("$origin/alice/_system/auth/grant", url.newBuilder().query(null).build().toString())
    assertEquals(
      mapOf(
        "client_id" to "dyn:abc",
        "redirect_uri" to "http://127.0.0.1:4711/cb",
        "state" to "g1",
        "service_client" to "svc:1",
        "scope" to "urn:a#read urn:b#write",
      ),
      url.queryParameterNames.associateWith { url.queryParameter(it) },
    )
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "building the URL sent a request")
  }

  @Test
  fun `the list reads every service client with its grants and last use`() {
    answer(
      "/alice/_system/auth/service-clients",
      200,
      """{"serviceClients":[$described,{"client_id":"ops-backup","client_name":null,"client_id_issued_at":1600000000,"last_used_at":null,"scope":"","origin":"provisioned"}]}""",
    )

    val listed = checkNotNull(serviceClients().list().body)
    assertThrows<UnsupportedOperationException> { (listed[0].scopes as MutableSet<String>).clear() }

    assertEquals(
      listOf(
        SempodsServiceClient.of(
          "svc:1", "Notes Sync", Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700000600), setOf("urn:a#read", "urn:b#write"), "installed",
        ),
        SempodsServiceClient.of("ops-backup", null, Instant.ofEpochSecond(1600000000), null, emptySet(), "provisioned"),
      ),
      listed,
    )
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("GET", sent.method.value)
    assertEquals("Bearer tok-1", sent.getFirstHeader("Authorization"))
  }

  @Test
  fun `a listed client without last_used_at is a decoding failure, and an explicit null is a client never used`() {
    answer("/alice/_system/auth/service-clients", 200, """{"serviceClients":[${described.replace(""""last_used_at":1700000600,""", "")}]}""")

    assertThrows<SempodsDecodingException> { serviceClients().list() }
  }

  @Test
  fun `a rotation posts to the client's own segment and reads the new secret`() {
    answer("/alice/_system/auth/service-clients/svc:1/secret", 200, """{"client_id":"svc:1","client_secret":"sc_new"}""")

    val rotated = checkNotNull(serviceClients().rotateSecret("svc:1").body)

    assertEquals("sc_new", rotated.clientSecret)
    assertFalse(rotated.toString().contains("sc_new"), rotated.toString())
    assertEquals("POST", server.retrieveRecordedRequests(request()).single().method.value)
  }

  @ParameterizedTest
  @ValueSource(strings = ["a/b", "..", ".", ""])
  fun `a client identifier that is not one path segment is refused before anything is sent`(clientId: String) {
    assertThrows<IllegalArgumentException> { serviceClients().rotateSecret(clientId) }
    assertThrows<IllegalArgumentException> { serviceClients().revoke(clientId) }

    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `a grant removal names the scopes in the query and reads what is left`() {
    answer("/alice/_system/auth/service-clients/svc:1/grants", 200, described.replace("urn:a#read urn:b#write", ""))

    val left = checkNotNull(serviceClients().removeGrants("svc:1", listOf("urn:a#read", "urn:b#write")).body)

    assertTrue(left.scopes.isEmpty())
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("DELETE", sent.method.value)
    assertEquals("urn:a#read urn:b#write", sent.getFirstQueryStringParameter("scope"))
  }

  @Test
  fun `a revocation answers whether the client was there`() {
    answer("/alice/_system/auth/service-clients/svc:1", 204, "")
    answer("/alice/_system/auth/service-clients/svc:2", 404, """{"error_description":"no such service client on this pod"}""")

    assertTrue(serviceClients().revoke("svc:1"))
    assertFalse(serviceClients().revoke("svc:2"))
    assertEquals(listOf("DELETE", "DELETE"), server.retrieveRecordedRequests(request()).map { it.method.value })
  }

  @ParameterizedTest
  @ValueSource(ints = [401, 403, 404, 409])
  fun `a management refusal keeps the status, the headers and the pod's document`(status: Int) {
    val error = """{"error":"insufficient_scope","error_description":"this needs an authorization carrying 'service-clients:manage'"}"""
    answer("/alice/_system/auth/service-clients/svc:1/secret", status, error, "WWW-Authenticate" to """Bearer error="insufficient_scope"""")

    val failure = assertThrows<SempodsStatusException> { serviceClients().rotateSecret("svc:1") }

    assertEquals(status, failure.status)
    assertEquals(error, failure.bodyExcerpt)
    assertEquals("""Bearer error="insufficient_scope"""", failure.headers["WWW-Authenticate"])
  }
}
