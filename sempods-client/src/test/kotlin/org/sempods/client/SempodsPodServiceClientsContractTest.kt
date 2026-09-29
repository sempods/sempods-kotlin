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

  /** What a service registering itself uses: a session without a credential. */
  private fun registering() = SempodsPodServiceClients(SempodsSession(alice), client)

  private fun answer(path: String, status: Int, body: String, vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status).withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request().withPath(path)).respond(response)
  }

  private val registration =
    """{"client_id":"svc:1","client_secret":"sc_secret","client_id_issued_at":1700000000,"client_secret_expires_at":0,""" +
      """"client_name":"Notes Sync","grant_types":["client_credentials"],"token_endpoint_auth_method":"client_secret_basic",""" +
      """"activation_expires_at":1700086400}"""

  private val described =
    """{"client_id":"svc:1","client_name":"Notes Sync","client_id_issued_at":1700000000,"last_used_at":1700000600,""" +
      """"scope":"urn:a#read urn:b#write","grants_version":3,"origin":"registered"}"""

  @Test
  fun `a service registers the confidential shape without a credential, and reads the secret and the deadline once`() {
    answer("/alice/_system/auth/register", 201, registration, "Cache-Control" to "no-store")

    val registered = checkNotNull(registering().register("Notes Sync").body)

    assertEquals("svc:1", registered.clientId)
    assertEquals("sc_secret", registered.clientSecret)
    assertEquals("Notes Sync", registered.clientName)
    assertEquals(Instant.ofEpochSecond(1700000000), registered.issuedAt)
    assertNull(registered.secretExpiresAt, "client_secret_expires_at 0 is a secret that does not expire")
    assertEquals(Instant.ofEpochSecond(1700086400), registered.activationExpiresAt)
    assertEquals(emptyList(), registered.redirectUris)
    assertFalse(registered.toString().contains("sc_secret"), registered.toString())
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("POST", sent.method.value)
    assertNull(sent.getFirstHeader("Authorization").ifEmpty { null }, "a registration needs no credential")
    assertEquals(
      json.readTree("""{"client_name":"Notes Sync","grant_types":["client_credentials"],"token_endpoint_auth_method":"client_secret_basic"}"""),
      json.readTree(String(sent.body.rawBytes, Charsets.UTF_8)),
    )
  }

  @Test
  fun `a registration with the owner's bearer sends it, and reads an active registration without a deadline`() {
    answer("/alice/_system/auth/register", 201, registration.replace(""","activation_expires_at":1700086400""", ""))

    val registered = checkNotNull(serviceClients().register("Notes Sync").body)

    assertNull(registered.activationExpiresAt)
    assertEquals("Bearer tok-1", server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization"))
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
  fun `a service registered with a redirect sends it without a response type, and reads it back`() {
    answer(
      "/alice/_system/auth/register", 201,
      registration.replace(""""client_name"""", """"redirect_uris":["http://127.0.0.1/cb"],"client_name""""),
    )

    val registered = checkNotNull(registering().register("Notes Sync", listOf("http://127.0.0.1/cb")).body)

    assertEquals(listOf("http://127.0.0.1/cb"), registered.redirectUris)
    val sent = json.readTree(String(server.retrieveRecordedRequests(request()).single().body.rawBytes, Charsets.UTF_8))
    assertEquals(json.readTree("""["http://127.0.0.1/cb"]"""), sent.get("redirect_uris"))
    assertNull(sent.get("response_types"), "a client authenticating with a secret has no browser flow")
  }

  @Test
  fun `a refused registration is the pod's error, kept on the failure`() {
    answer("/alice/_system/auth/register", 400, """{"error":"invalid_redirect_uri"}""")

    val failure = assertThrows<SempodsStatusException> { registering().register("Notes Sync", listOf("http://app.example/cb")) }

    assertEquals(400, failure.status)
  }

  @Test
  fun `the consent URL is the pod's own and names the service, its state and its return address`() {
    val url = serviceClients().consentUrl("svc:1", "c1", "http://127.0.0.1:4711/cb")

    assertEquals("$origin/alice/_system/auth/service-consent", url.newBuilder().query(null).build().toString())
    assertEquals(
      mapOf("client_id" to "svc:1", "state" to "c1", "redirect_uri" to "http://127.0.0.1:4711/cb"),
      url.queryParameterNames.associateWith { url.queryParameter(it) },
    )
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "building the URL sent a request")
  }

  @Test
  fun `a consent URL without a return address carries none, and suggests no rows`() {
    val url = serviceClients().consentUrl("svc:1", "c1")

    assertEquals(setOf("client_id", "state"), url.queryParameterNames)
  }

  @Test
  fun `the list reads every service client with its grants and last use`() {
    answer(
      "/alice/_system/auth/service-clients",
      200,
      """{"serviceClients":[$described,{"client_id":"ops-backup","client_name":null,"client_id_issued_at":1600000000,"last_used_at":null,"scope":"","grants_version":0,"origin":"provisioned"}]}""",
    )

    val listed = checkNotNull(serviceClients().list().body)
    assertThrows<UnsupportedOperationException> { (listed[0].scopes as MutableSet<String>).clear() }

    assertEquals(
      listOf(
        SempodsServiceClient.of(
          "svc:1", "Notes Sync", Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700000600), setOf("urn:a#read", "urn:b#write"), 3L,
          "registered",
        ),
        SempodsServiceClient.of("ops-backup", null, Instant.ofEpochSecond(1600000000), null, emptySet(), 0L, "provisioned"),
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
  fun `a listed client without grants_version is a decoding failure`() {
    answer("/alice/_system/auth/service-clients", 200, """{"serviceClients":[${described.replace(""""grants_version":3,""", "")}]}""")

    assertThrows<SempodsDecodingException> { serviceClients().list() }
  }

  @Test
  fun `a single read gets the client's own segment`() {
    answer("/alice/_system/auth/service-clients/svc:1", 200, described, "ETag" to "\"3\"")

    val read = checkNotNull(serviceClients().get("svc:1").body)

    assertEquals(3L, read.grantsVersion)
    assertEquals(setOf("urn:a#read", "urn:b#write"), read.scopes)
    assertEquals("GET", server.retrieveRecordedRequests(request()).single().method.value)
  }

  @Test
  fun `a grants replace puts the scopes as a JSON array, at the version it names`() {
    answer("/alice/_system/auth/service-clients/svc:1/grants", 200, described.replace("urn:a#read urn:b#write", "urn:c#read").replace(":3,", ":4,"))

    val replaced = checkNotNull(serviceClients().replaceGrants("svc:1", listOf("urn:c#read", "urn:c#read"), 3L).body)

    assertEquals(setOf("urn:c#read"), replaced.scopes)
    assertEquals(4L, replaced.grantsVersion)
    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("PUT", sent.method.value)
    assertEquals("\"3\"", sent.getFirstHeader("If-Match"))
    assertEquals(json.readTree("""["urn:c#read"]"""), json.readTree(String(sent.body.rawBytes, Charsets.UTF_8)))
    assertTrue(sent.getFirstHeader("Content-Type").startsWith("application/json"), sent.getFirstHeader("Content-Type"))
  }

  @Test
  fun `an empty grants replace sends an empty array`() {
    answer("/alice/_system/auth/service-clients/svc:1/grants", 200, described.replace("urn:a#read urn:b#write", ""))

    assertTrue(checkNotNull(serviceClients().replaceGrants("svc:1", emptyList(), 0L).body).scopes.isEmpty())
    assertEquals(json.readTree("[]"), json.readTree(String(server.retrieveRecordedRequests(request()).single().body.rawBytes, Charsets.UTF_8)))
  }

  @Test
  fun `a stale grants version keeps the pod's 412`() {
    answer("/alice/_system/auth/service-clients/svc:1/grants", 412, """{"error_description":"the grants changed"}""")

    val failure = assertThrows<SempodsStatusException> { serviceClients().replaceGrants("svc:1", listOf("urn:c#read"), 2L) }

    assertEquals(412, failure.status)
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
