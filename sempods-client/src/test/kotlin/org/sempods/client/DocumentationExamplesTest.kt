package org.sempods.client

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import java.util.UUID
import java.time.Duration
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The snippets printed in the client and OAuth guides, exercised against recorded HTTP requests. */
class DocumentationExamplesTest : MockPodTest() {

  private val json = JsonMapper()

  private val http = SempodsOkHttp.install(OkHttpClient.Builder()).build()

  @AfterAll
  fun stopClient() {
    http.shutDown()
  }

  @Test
  fun `the public read example sends no credential`() {
    val podUrl = "$origin/alice"
    val resourceIri = "$podUrl/events/summer-party"
    server.`when`(request().withPath("/alice/events/summer-party"))
      .respond(response().withStatusCode(200).withBody("""{"@id":"$resourceIri"}"""))

    // doc-example:start public-read
    val session = SempodsSession(SempodsPodBase.of(podUrl))
    val pod = SempodsPod(session, http)
    val result = pod.resources().getText(resourceIri)
    if (result.status == 200) {
      val jsonLd = checkNotNull(result.body)
      println(jsonLd)
    }
    // doc-example:end public-read

    assertEquals(200, result.status)
    assertTrue(server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization").isEmpty())
  }

  @Test
  fun `the service example keeps Basic at the token endpoint and renews a refused bearer`() {
    val podUrl = "$origin/alice"
    val clientId = "svc:notes"
    val clientSecret = "a+b"
    server.`when`(request().withPath("/alice/_system/auth/token"))
      .respond(response().withBody("""{"access_token":"service-token","token_type":"Bearer","expires_in":600}"""))
    server.`when`(request().withPath("/alice/_system/contexts"))
      .respond(response().withBody("[]"))

    // doc-example:start service-access
    val base = SempodsPodBase.of(podUrl)
    val credentials = SempodsSession(base, SempodsRequestAuth.clientSecretBasic(clientId, clientSecret))
    val bearer = SempodsRequestAuth.refreshable(supplier = { _, attempt ->
      val tokens = SempodsPodTokens(credentials, attempt.calls(http))
      checkNotNull(tokens.clientCredentials().body).accessToken
    })
    val pod = SempodsPod(SempodsSession(base, bearer), http)
    val contexts = pod.contexts().listText()
    // doc-example:end service-access

    assertEquals(200, contexts.status)
    val tokenRequest = server.retrieveRecordedRequests(request().withPath("/alice/_system/auth/token")).single()
    assertEquals("grant_type=client_credentials", tokenRequest.bodyAsString)
    assertEquals("Basic c3ZjJTNBbm90ZXM6YSUyQmI=", tokenRequest.getFirstHeader("Authorization"))
    assertEquals("Bearer service-token", server.retrieveRecordedRequests(request().withPath("/alice/_system/contexts")).single().getFirstHeader("Authorization"))
    pod.contexts().listText()
    assertEquals(1, server.retrieveRecordedRequests(request().withPath("/alice/_system/auth/token")).size)

    server.clear(request().withPath("/alice/_system/contexts"))
    server.`when`(request().withPath("/alice/_system/contexts"), org.mockserver.matchers.Times.once())
      .respond(response().withStatusCode(401))
    server.`when`(request().withPath("/alice/_system/contexts"))
      .respond(response().withBody("[]"))
    assertEquals(200, pod.contexts().listText().status)
    assertEquals(2, server.retrieveRecordedRequests(request().withPath("/alice/_system/auth/token")).size)
  }

  @Test
  fun `the service setup example registers anonymously and checks catalogue visibility`() {
    val podUrl = "$origin/alice"
    val notes = "$podUrl/_system/contexts/notes"
    server.`when`(request().withPath("/alice/_system/auth/register"))
      .respond(response().withStatusCode(201).withBody("""{"client_id":"svc:notes","client_secret":"secret","client_id_issued_at":1,"client_secret_expires_at":0,"activation_expires_at":9999999999}"""))
    server.`when`(request().withPath("/alice/_system/auth/token"))
      .respond(response().withBody("""{"access_token":"service-token","token_type":"Bearer","expires_in":600}"""))
    server.`when`(request().withPath("/alice/_system/contexts"))
      .respond(response().withBody("""{"http://www.w3.org/ns/sparql-service-description#namedGraph":[{"@id":"$notes"}]}"""))

    // doc-example:start service-register
    val base = SempodsPodBase.of(podUrl)
    val registering = SempodsPodServiceClients(SempodsSession(base), http)
    val service = checkNotNull(registering.register("Notes Sync").body)
    val state = UUID.randomUUID().toString()
    val consentUrl = registering.consentUrl(service.clientId, state)
    // doc-example:end service-register

    assertEquals("/alice/_system/auth/service-consent", consentUrl.encodedPath)
    assertEquals(service.clientId, consentUrl.queryParameter("client_id"))
    assertEquals(setOf("client_id", "state"), consentUrl.queryParameterNames)
    assertTrue(service.activationExpiresAt != null)
    val registered = server.retrieveRecordedRequests(request().withPath("/alice/_system/auth/register")).single()
    assertTrue(registered.getFirstHeader("Authorization").isEmpty())
    assertTrue(registered.bodyAsString.contains("client_credentials"))
    assertTrue(registered.bodyAsString.contains("client_secret_basic"))

    // doc-example:start service-wait
    val credentials = SempodsSession(base,
      SempodsRequestAuth.clientSecretBasic(service.clientId, service.clientSecret))
    val waiting = SempodsServiceAccessWait(credentials, http)
    val outcome = waiting.await(listOf(notes), Duration.ofMinutes(10))
    // doc-example:end service-wait

    assertEquals(SempodsServiceAccessWait.Outcome.REACHABLE, outcome)
    assertEquals("Bearer service-token", server.retrieveRecordedRequests(request().withPath("/alice/_system/contexts")).single().getFirstHeader("Authorization"))
  }

  @Test
  fun `the management example assigns a complete grant set at the version read`() {
    val base = SempodsPodBase.of("$origin/alice")
    val manageToken = "owner-management"
    val notes = "${base.url}/_system/contexts/notes"
    val path = "/alice/_system/auth/service-clients/svc:backup"
    server.`when`(request().withPath("/alice/_system/auth/register"))
      .respond(response().withStatusCode(201).withBody("""{"client_id":"svc:backup","client_secret":"secret","client_id_issued_at":1,"client_secret_expires_at":0}"""))
    server.`when`(request().withMethod("GET").withPath(path))
      .respond(response().withBody("""{"client_id":"svc:backup","client_id_issued_at":1,"last_used_at":null,"scope":"","grants_version":7,"origin":"registered"}"""))
    server.`when`(request().withMethod("PUT").withPath("$path/grants"))
      .respond(response().withBody("""{"client_id":"svc:backup","client_id_issued_at":1,"last_used_at":null,"scope":"$notes#read","grants_version":8,"origin":"registered"}"""))

    // doc-example:start service-manage
    val managing = SempodsPodServiceClients(
      SempodsSession(base, SempodsRequestAuth.bearer(manageToken)), http)
    val service = checkNotNull(managing.register("Backup").body)
    val current = checkNotNull(managing.get(service.clientId).body)
    val updated = managing.replaceGrants(service.clientId, listOf("$notes#read"), current.grantsVersion)
    // doc-example:end service-manage

    assertEquals(8L, checkNotNull(updated.body).grantsVersion)
    assertEquals(listOf("$notes#read"), updated.body!!.scopes.toList())
    val replace = server.retrieveRecordedRequests(request().withMethod("PUT")).single()
    assertEquals("\"7\"", replace.getFirstHeader("If-Match"))
    assertEquals(json.readTree("[\"$notes#read\"]"), json.readTree(replace.bodyAsString))
    assertTrue(server.retrieveRecordedRequests(request()).all { it.getFirstHeader("Authorization") == "Bearer $manageToken" })
  }

  @Test
  fun `the user example sends PKCE and redeems the checked callback`() {
    val podUrl = "$origin/alice"
    val redirectUri = "http://127.0.0.1:4711/callback"
    server.`when`(request().withPath("/alice/_system/auth/register"))
      .respond(response().withStatusCode(201).withBody("""{"client_id":"dyn:notes","redirect_uris":["$redirectUri"]}"""))
    server.`when`(request().withPath("/alice/_system/auth/token"))
      .respond(response().withBody("""{"access_token":"user-token","token_type":"Bearer","expires_in":3600}"""))

    // doc-example:start user-authorize
    val base = SempodsPodBase.of(podUrl)
    val anonymous = SempodsSession(base)
    val authorization = SempodsPodAuthorization(anonymous, http)
    val registration = checkNotNull(authorization.registerClient("Notes", listOf(redirectUri)).body)
    val clientId = registration.clientId
    val pkce = SempodsPkce.generate()
    val state = UUID.randomUUID().toString()
    val consentUrl = authorization.authorizationUrl(clientId, redirectUri, "", state, pkce)
    // doc-example:end user-authorize

    assertEquals("S256", consentUrl.queryParameter("code_challenge_method"))
    assertEquals(pkce.challenge, consentUrl.queryParameter("code_challenge"))
    val callbackQuery = "code=approved-code&state=$state"

    // doc-example:start user-redeem
    val answer = authorization.readRedirect(callbackQuery, state)
    check(answer.isApproved) { "Authorization failed: ${answer.error}" }
    val tokens = SempodsPodTokens(anonymous, http)
    val token = checkNotNull(tokens.authorizationCode(clientId, checkNotNull(answer.code), redirectUri, pkce.verifier).body)
    val pod = SempodsPod(SempodsSession(base, SempodsRequestAuth.bearer(token.accessToken)), http)
    // doc-example:end user-redeem

    assertEquals(base, pod.session.podBase)
    val sent = server.retrieveRecordedRequests(request().withPath("/alice/_system/auth/token")).single()
    assertTrue(sent.bodyAsString.contains("code_verifier=${pkce.verifier}"))
    assertTrue(sent.getFirstHeader("Authorization").isEmpty())
    assertFalse(authorization.readRedirect("error=access_denied&state=$state", state).isApproved)
  }
}
