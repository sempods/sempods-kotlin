package org.sempods.management

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsStatusException
import org.slf4j.event.Level
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What each grant-management operation puts on the wire, and what it reads back. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsPodGrantsHttpTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var http: OkHttpClient
  private lateinit var grants: SempodsPodGrants

  private val notes = "https://pods.example/alice/_system/contexts/notes"

  private val work = "https://pods.example/alice/_system/contexts/notes/work"

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    http = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    grants = SempodsPodGrants(
      SempodsSession(SempodsPodBase.of("http://localhost:${mockServer.port}/alice"), SempodsRequestAuth.bearer("tok-1")),
      http,
    )
  }

  @AfterAll
  fun stopServer() {
    mockServer.stop()
    http.dispatcher.executorService.shutdown()
    http.connectionPool.evictAll()
  }

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
  }

  private fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

  private fun answer(path: String, status: Int, body: String = "") {
    mockServer.`when`(request().withPath(path)).respond(response().withStatusCode(status).withBody(body))
  }

  @Test
  fun `the counts are read for every context at once`() {
    answer("/alice/_system/auth/grants", 200, """{"contexts":[{"context_iri":"$notes","people":2,"apps":1,"services":0}]}""")

    val counts = checkNotNull(grants.counts().body)

    assertEquals(listOf(SempodsContextHolderCounts.of(notes, people = 2, apps = 1, services = 0)), counts)
    val sent = mockServer.retrieveRecordedRequests(request()).single()
    assertEquals("GET", sent.method.value)
    assertEquals("Bearer tok-1", sent.getFirstHeader("Authorization"))
  }

  @Test
  fun `the holders of a context are addressed by its IRI in base64url and read grouped`() {
    answer(
      "/alice/_system/auth/grants/${b64(work)}", 200,
      """{"context_iri":"$work",""" +
        """"people":[{"web_id":"https://id.example/bob","permissions":["read","write","manage"],""" +
        """"grants":[{"permission":"read","inherited_from":null},{"permission":"manage","inherited_from":"$notes"}],""" +
        """"delegations":[{"client_id":"dyn:notes","client_name":"Notes","permissions":["read"],""" +
        """"grants":[{"permission":"read","inherited_from":null}],"last_used_at":"2026-11-26T09:14:00Z"}]}],""" +
        """"apps":[{"client_id":"did:web:app.example","permissions":["read","write"],"grants":[{"permission":"write","inherited_from":null}]}],""" +
        """"services":[{"client_id":"svc:1","client_name":"Notes Sync","permissions":["read"],""" +
        """"grants":[{"permission":"read","inherited_from":null}],"last_used_at":"2026-11-25T08:00:00Z","grants_version":3}]}""",
    )

    val holders = checkNotNull(grants.holders(work).body)

    assertEquals(work, holders.contextIri)
    val bob = holders.people.single()
    assertEquals(listOf("read", "write", "manage"), bob.permissions)
    assertTrue(bob.grants[0].isDirect)
    assertEquals(notes, bob.grants[1].inheritedFrom)
    val notesApp = bob.delegations.single()
    assertEquals("Notes", notesApp.clientName)
    assertEquals(Instant.parse("2026-11-26T09:14:00Z"), notesApp.lastUsedAt)
    val ownerApp = holders.apps.single()
    assertNull(ownerApp.clientName)
    assertNull(ownerApp.lastUsedAt, "an absent member is no recorded use")
    assertEquals(3L, holders.services.single().grantsVersion)
  }

  @Test
  fun `a body that is not the route's document is a decoding failure`() {
    answer(
      "/alice/_system/auth/grants/${b64(notes)}", 200,
      """{"context_iri":"$notes","people":[],"apps":[{"client_id":"a","permissions":[],"grants":[],"last_used_at":"1700000000"}],"services":[]}""",
    )
    answer("/alice/_system/auth/grants", 200, """{"contexts":[{"context_iri":"$notes","people":-1,"apps":0,"services":0}]}""")

    assertThrows<SempodsDecodingException> { grants.holders(notes) }
    assertThrows<SempodsDecodingException> { grants.counts() }
  }

  @Test
  fun `withdrawing a person, an app and a service sends a DELETE to the holder's route`() {
    answer("/alice/_system/auth/grants/${b64(notes)}/people/${b64("https://id.example/bob")}", 204)
    answer("/alice/_system/auth/grants/${b64(notes)}/apps/${b64("dyn:notes")}", 404)
    answer("/alice/_system/auth/grants/${b64(notes)}/services/${b64("svc:1")}", 204)

    assertTrue(grants.withdrawPerson(notes, "https://id.example/bob"))
    assertFalse(grants.withdrawApp(notes, "dyn:notes"), "404: nothing held there")
    assertTrue(grants.withdrawService(notes, "svc:1", grantsVersion = 3))

    val sent = mockServer.retrieveRecordedRequests(request())
    assertEquals(listOf("DELETE", "DELETE", "DELETE"), sent.map { it.method.value })
    assertEquals("\"3\"", sent.last().getFirstHeader("If-Match"), "the version as a strong ETag")
    assertTrue(sent.dropLast(1).all { it.getFirstHeader("If-Match").isEmpty() })
  }

  @Test
  fun `an inherited grant and a stale version are refusals carrying the pod's answer`() {
    answer("/alice/_system/auth/grants/${b64(work)}/people/${b64("https://id.example/bob")}", 409, """{"inherited_from":["$notes"]}""")
    answer("/alice/_system/auth/grants/${b64(notes)}/services/${b64("svc:1")}", 412)

    val inherited = assertThrows<SempodsStatusException> { grants.withdrawPerson(work, "https://id.example/bob") }
    assertEquals(409, inherited.status)
    assertTrue(notes in inherited.bodyExcerpt, "the error document names the ancestor")
    assertEquals(412, assertThrows<SempodsStatusException> { grants.withdrawService(notes, "svc:1", grantsVersion = 2) }.status)
  }
}
