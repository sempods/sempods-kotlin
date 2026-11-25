package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.clientSecretBasicHeader
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.spi.SERVICE_CLIENT_TYPE
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A `did:web` service on a pod: it registers with a software statement its DID document's key
 * signed, the owner activates it in the service consent, and it gets tokens with `private_key_jwt`
 * (RFC 7523 §2.2) and no secret. The DID document is served from loopback by [DidWebServiceFixture].
 */
class DidWebServiceClientHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var services: ServiceAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  // ── Registration ────────────────────────────────────────────────────────────

  @Test
  fun `a did-web service registers under its identifier, with no secret`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()

    val response = register(owned.pod, service.statement(owned.pod))

    assertEquals(201, response.statusCode, response.responseBody)
    val body = json(response)
    assertEquals(service.did, body["client_id"])
    assertNull(body["client_secret"], "a did:web service proves itself with its keys")
    assertEquals("private_key_jwt", body["token_endpoint_auth_method"])
    assertEquals(listOf("client_credentials"), body["grant_types"])
    assertEquals("Owner Console", body["client_name"])
    assertNotNull(body["activation_expires_at"], "provisional until the owner consents")
  }

  @Test
  fun `a repeat answers the registration that stands`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()
    val first = json(register(owned.pod, service.statement(owned.pod)))

    val again = register(owned.pod, service.statement(owned.pod), name = "Renamed")

    assertEquals(201, again.statusCode, again.responseBody)
    val body = json(again)
    assertEquals(first["client_id"], body["client_id"])
    assertEquals(first["client_id_issued_at"], body["client_id_issued_at"])
    assertEquals("Owner Console", body["client_name"], "what the owner may have consented under stays")
  }

  @Test
  fun `the statement's metadata takes precedence over the body's`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()

    val response = register(owned.pod, service.statement(owned.pod, claims = mapOf("client_name" to "Stated Name")), name = "Body Name")

    assertEquals("Stated Name", json(response)["client_name"])
  }

  @Test
  fun `a registration without a statement, or with one that does not verify, is refused`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()
    val stranger = ECKeyGenerator(Curve.P_256).keyID("${service.did}#key-ec").generate()

    val refusals = mapOf(
      "no statement" to register(owned.pod, statement = null),
      "a key the document does not name" to register(owned.pod, service.sign(registerEndpoint(owned.pod), key = stranger)),
      "the token endpoint as audience" to register(owned.pod, service.assertion(owned.pod)),
      "a statement stating another profile" to
        register(owned.pod, service.statement(owned.pod, claims = mapOf("token_endpoint_auth_method" to "client_secret_basic"))),
    )

    refusals.forEach { (case, response) ->
      assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
      assertEquals("invalid_software_statement", json(response)["error"], case)
    }
    assertEquals(400, services.open(owned.pod, service.did, owned.cookie).statusCode, "nothing was registered")
  }

  @Test
  fun `a statement is accepted once`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()
    val statement = service.statement(owned.pod)
    assertEquals(201, register(owned.pod, statement).statusCode)

    val replayed = register(owned.pod, statement)

    assertEquals(400, replayed.statusCode, replayed.responseBody)
    assertEquals("invalid_software_statement", json(replayed)["error"])
  }

  // ── Token endpoint ──────────────────────────────────────────────────────────

  @Test
  fun `a valid assertion gets a token, which reaches what the owner granted`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = activated(owned, setOf("$c#read"))

    val minted = token(owned.pod, service.assertion(owned.pod))

    assertEquals(200, minted.statusCode, minted.responseBody)
    val accessToken = json(minted)["access_token"] as String
    val claims = SignedJWT.parse(accessToken).jwtClaimsSet
    assertEquals(service.did, claims.subject)
    assertEquals(service.did, claims.getStringClaim("client_id"))
    assertEquals(SERVICE_CLIENT_TYPE, claims.getStringClaim("client_type"))
    assertEquals(listOf(c), services.contexts(owned.pod, accessToken))
  }

  @Test
  fun `each of the document's keys authenticates, and the issuer is an audience too`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))

    assertEquals(200, token(owned.pod, service.assertion(owned.pod, key = service.rsaKey)).statusCode)
    assertEquals(200, token(owned.pod, service.sign(DidWebService.issuer(owned.pod))).statusCode)
  }

  @Test
  fun `a wrong audience, a replay, an expired assertion and an unknown key are each invalid_client`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))
    val spent = service.assertion(owned.pod)
    assertEquals(200, token(owned.pod, spent).statusCode)
    val stranger = ECKeyGenerator(Curve.P_256).keyID("${service.did}#key-stranger").generate()

    val refusals = mapOf(
      "wrong audience" to token(owned.pod, service.sign("${SempodsModule.config.apiBaseUrl}another-pod/_system/auth/token")),
      "replayed jti" to token(owned.pod, spent),
      "expired" to token(owned.pod, service.assertion(owned.pod, lifetimeSeconds = -120)),
      "unknown key" to token(owned.pod, service.assertion(owned.pod, key = stranger)),
      "a key id the document names, signed by another key" to
        token(owned.pod, service.sign(DidWebService.tokenEndpoint(owned.pod), key = stranger, keyId = service.ecKey.keyID)),
      "an expiry too far ahead" to token(owned.pod, service.assertion(owned.pod, lifetimeSeconds = 3600)),
    )

    refusals.forEach { (case, response) ->
      assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
      assertEquals("invalid_client", json(response)["error"], case)
    }
  }

  @Test
  fun `an assertion for a pod the service is not registered on is invalid_client`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()

    val response = token(owned.pod, service.assertion(owned.pod))

    assertEquals(400, response.statusCode, response.responseBody)
    assertEquals("invalid_client", json(response)["error"])
  }

  @Test
  fun `a did-web service has no secret to authenticate with`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))

    val basic = services.token(owned.pod, service.did, "any secret")

    assertEquals(401, basic.statusCode, basic.responseBody)
    assertEquals("invalid_client", json(basic)["error"])
  }

  @Test
  fun `HTTP Basic beside an assertion is refused`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))

    val both = tokenRequest(owned.pod, service.assertion(owned.pod))
      .addHeader("Authorization", clientSecretBasicHeader(service.did, "secret"))
      .execute()

    assertEquals(400, both.statusCode, both.responseBody)
    assertEquals("invalid_request", json(both)["error"])
  }

  @Test
  fun `a client_id beside the assertion has to name its subject`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))
    val other = activated(owned, setOf("${owned.context("d")}#read"))

    val mismatched = tokenRequest(owned.pod, service.assertion(owned.pod), clientId = other.did).execute()
    val matching = tokenRequest(owned.pod, service.assertion(owned.pod), clientId = service.did).execute()

    assertEquals("invalid_client", json(mismatched)["error"], mismatched.responseBody)
    assertEquals(200, matching.statusCode, matching.responseBody)
  }

  @Test
  fun `a pending registration authenticates and mints nothing`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()
    assertEquals(201, register(owned.pod, service.statement(owned.pod)).statusCode)

    val response = token(owned.pod, service.assertion(owned.pod))

    assertEquals(400, response.statusCode, response.responseBody)
    assertEquals("invalid_scope", json(response)["error"], "authenticated, and not yet granted anything")
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /** A did:web service registered on [owned]'s pod and activated by its owner with [scopes]. */
  private fun activated(owned: Owned, scopes: Set<String>): DidWebService {
    val service = DidWebServiceFixture.newService()
    val registered = register(owned.pod, service.statement(owned.pod))
    assertEquals(201, registered.statusCode, registered.responseBody)
    services.confirm(owned.pod, service.did, owned.cookie, scopes)
    return service
  }

  private fun register(pod: PodDbo, statement: String?, name: String = "Owner Console"): TestHttpResponse {
    val body = linkedMapOf<String, Any>(
      "client_name" to name,
      "grant_types" to listOf("client_credentials"),
      "token_endpoint_auth_method" to "private_key_jwt",
    )
    statement?.let { body["software_statement"] = it }
    return http.preparePost(registerEndpoint(pod))
      .addHeader("Content-Type", "application/json")
      .setBody(JsonMappers.default().writeValueAsString(body))
      .execute()
  }

  private fun token(pod: PodDbo, assertion: String): TestHttpResponse = tokenRequest(pod, assertion).execute()

  private fun tokenRequest(pod: PodDbo, assertion: String, clientId: String? = null) =
    http.preparePost(DidWebService.tokenEndpoint(pod))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(
        "grant_type=client_credentials" +
          "&client_assertion_type=${enc("urn:ietf:params:oauth:client-assertion-type:jwt-bearer")}" +
          "&client_assertion=${enc(assertion)}" +
          (clientId?.let { "&client_id=${enc(it)}" } ?: ""),
      )

  private fun registerEndpoint(pod: PodDbo) = "${DidWebService.issuer(pod)}/_system/auth/register"

  @Suppress("UNCHECKED_CAST")
  private fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val cookie: String get() = signIn(pod.name, webId).cookie

    /** Registers a private context at [path] and answers its IRI. */
    fun context(path: String): String {
      val uri = sempodsUriBuilder.buildContext(pod.name, path)
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    return Owned(sempodsTestFactory.newPod(ownerUser = owner), webIdUriDeriver.deriveFromEmail(owner.email))
  }
}
