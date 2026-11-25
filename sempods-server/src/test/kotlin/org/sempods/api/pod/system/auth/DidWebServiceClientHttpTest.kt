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
import com.mongodb.client.MongoDatabase
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.didweb.ClientAssertionJtiStore
import org.sempods.pods.oauth.didweb.DidWebAssertion
import org.sempods.pods.oauth.didweb.DidWebClientAuthentication
import org.sempods.pods.oauth.didweb.DidWebKeys
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.util.UUID
import org.sempods.pods.oauth.spi.SERVICE_CLIENT_TYPE
import org.sempods.pods.oauth.spi.SERVICE_REGISTRATION_CLAIM
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

  @Inject
  private lateinit var didWeb: DidWebAccessFlow

  @Inject
  private lateinit var serviceClientStore: PodServiceClientStore

  @Inject
  private lateinit var podTokenIssuer: PodTokenIssuer

  @Inject
  private lateinit var db: MongoDatabase

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
    val service = DidWebServiceFixture.newService()
    val stranger = ECKeyGenerator(Curve.P_256).keyID("${service.did}#key-ec").generate()

    // Each on a pod of its own: a self-registration spends the pod's budget before it is checked.
    val refusals = mapOf<String, (PodDbo) -> TestHttpResponse>(
      "no statement" to { pod -> register(pod, statement = null) },
      "a key the document does not name" to { pod -> register(pod, service.sign(registerEndpoint(pod), key = stranger)) },
      "the token endpoint as audience" to { pod -> register(pod, service.assertion(pod)) },
      "the issuer as audience" to { pod -> register(pod, service.sign(DidWebService.issuer(pod))) },
      "two audiences" to { pod ->
        register(pod, service.sign("", audiences = listOf(registerEndpoint(pod), DidWebService.tokenEndpoint(pod))))
      },
      "a statement stating another profile" to { pod ->
        register(pod, service.statement(pod, claims = mapOf("token_endpoint_auth_method" to "client_secret_basic")))
      },
      "a statement asking for a scope" to { pod -> register(pod, service.statement(pod, claims = mapOf("scope" to "public-read"))) },
      "a signed redirect_uris that is not a list" to { pod ->
        register(pod, service.statement(pod, claims = mapOf("redirect_uris" to "https://evil.example/cb")))
      },
      "a signed client_name that is not a name" to { pod -> register(pod, service.statement(pod, claims = mapOf("client_name" to 7))) },
      "a signed client_name of null" to { pod -> register(pod, service.statement(pod, nullClaims = setOf("client_name"))) },
      "a signed redirect_uris of null" to { pod ->
        register(pod, service.statement(pod, nullClaims = setOf("redirect_uris")), redirectUris = listOf("https://evil.example/cb"))
      },
    )

    refusals.forEach { (case, send) ->
      val owned = ownedPod()
      val response = send(owned.pod)
      assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
      assertEquals("invalid_software_statement", json(response)["error"], case)
      assertEquals(400, services.open(owned.pod, service.did, owned.cookie).statusCode, "$case: nothing was registered")
    }
  }

  @Test
  fun `a signed redirect_uris of the wrong type never lets the body's stand in`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()

    val response = register(
      owned.pod,
      service.statement(owned.pod, claims = mapOf("redirect_uris" to listOf(7))),
      redirectUris = listOf("https://evil.example/cb"),
    )

    assertEquals(400, response.statusCode, response.responseBody)
    assertEquals(400, services.open(owned.pod, service.did, owned.cookie).statusCode, "nothing was registered")
  }

  @Test
  fun `an identifier with a port is refused, since no route could name it`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService(port = 8443)

    val response = register(owned.pod, service.statement(owned.pod))

    assertEquals(400, response.statusCode, response.responseBody)
    assertEquals("invalid_software_statement", json(response)["error"])
    assertTrue("port" in (json(response)["error_description"] as String), response.responseBody)
  }

  @Test
  fun `an identifier with a segment no route can name is refused before its document is read`() {
    val owned = ownedPod()
    for (did in listOf("did:web:did.test:svc-${UUID.randomUUID()}/evil", "did:web:did.test:..", "did:web:did.test:a+b")) {
      val service = DidWebService(did) { }

      val response = register(owned.pod, service.statement(owned.pod))

      assertEquals("invalid_software_statement", json(response)["error"], "$did: ${response.responseBody}")
      assertTrue("did:web" in (json(response)["error_description"] as String), "refused for its form: ${response.responseBody}")
    }
  }

  @Test
  fun `a token without the registration claim holds nothing of a did-web service's grants`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))

    // What a public caller of the issuer, or a verifier of a deployment's own, could hand in.
    val unbound = podTokenIssuer.issueServiceToken(pod = owned.pod.name, clientId = service.did, scopes = emptySet())

    assertEquals(emptyList(), services.contexts(owned.pod, unbound))
  }

  @Test
  fun `no room to read the document is retryable, not a refusal`() {
    val service = DidWebServiceFixture.newService()
    val full = DidWebClientAuthentication(
      DidWebKeys(fetcher = DidWebServiceFixture.fetcher, allowLoopbackHttp = false, maxConcurrentReads = 0),
      ClientAssertionJtiStore(db),
    )
    val owned = ownedPod()

    val verified = full.verify(service.assertion(owned.pod), audiences = setOf(DidWebService.tokenEndpoint(owned.pod)))

    assertEquals(DidWebAssertion.Busy, verified)
  }

  @Test
  fun `a spent budget refuses a self-registration before the DID document is read`() {
    val owned = ownedPod()
    repeat(SempodsModule.config.registerRateLimitServiceBurst) { services.register(owned.pod, name = "Filler $it") }
    val service = DidWebServiceFixture.newService()

    val response = register(owned.pod, service.statement(owned.pod))

    assertEquals(429, response.statusCode, response.responseBody)
    assertEquals(0, service.reads(), "the pod asked the service's host nothing")
  }

  @Test
  fun `a service registers again as soon as its provisional registration has expired`() {
    val owned = ownedPod()
    val service = DidWebServiceFixture.newService()
    assertEquals(201, register(owned.pod, service.statement(owned.pod)).statusCode)
    services.expire(service.did)

    val again = register(owned.pod, service.statement(owned.pod))

    assertEquals(201, again.statusCode, again.responseBody)
    assertNotNull(json(again)["activation_expires_at"])
    assertEquals(200, services.open(owned.pod, service.did, owned.cookie).statusCode, "the new registration stands")
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
    assertEquals(serviceClientStore.find(owned.pod.hosted.id, service.did)?.id?.value, claims.getStringClaim(SERVICE_REGISTRATION_CLAIM))
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
  fun `a software statement is no client assertion, and one audience is all an assertion names`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))

    val refusals = mapOf(
      "a statement for the registration endpoint" to token(owned.pod, service.statement(owned.pod)),
      "two audiences" to token(
        owned.pod,
        service.sign("", audiences = listOf(DidWebService.tokenEndpoint(owned.pod), registerEndpoint(owned.pod))),
      ),
    )

    refusals.forEach { (case, response) ->
      assertEquals(400, response.statusCode, "$case: ${response.responseBody}")
      assertEquals("invalid_client", json(response)["error"], case)
    }
  }

  @Test
  fun `an assertion refused for its audience leaves its jti good where it was meant to go`() {
    val owned = ownedPod()
    val service = activated(owned, setOf("${owned.context("c")}#read"))
    val jti = "jti-${java.util.UUID.randomUUID()}"

    val misdirected = token(owned.pod, service.sign("${SempodsModule.config.apiBaseUrl}another-pod/_system/auth/token", jti = jti))
    val meant = token(owned.pod, service.assertion(owned.pod, jti = jti))

    assertEquals("invalid_client", json(misdirected)["error"], misdirected.responseBody)
    assertEquals(200, meant.statusCode, meant.responseBody)
  }

  @Test
  fun `a token holds for the registration it was issued to, and not for one made again under its identifier`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = activated(owned, setOf("$c#read"))
    val before = json(token(owned.pod, service.assertion(owned.pod)))["access_token"] as String
    val standing = checkNotNull(serviceClientStore.find(owned.pod.hosted.id, service.did))
    assertTrue(serviceClientStore.remove(owned.pod.hosted.id, service.did, standing.id))

    assertEquals(201, register(owned.pod, service.statement(owned.pod)).statusCode)
    services.confirm(owned.pod, service.did, owned.cookie, setOf("$c#read"))
    val after = json(token(owned.pod, service.assertion(owned.pod)))["access_token"] as String

    assertEquals(emptyList(), services.contexts(owned.pod, before), "the earlier registration's token reaches nothing")
    assertEquals(listOf(c), services.contexts(owned.pod, after))
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

  private fun register(pod: PodDbo, statement: String?, name: String = "Owner Console", redirectUris: List<String>? = null) =
    didWeb.register(pod, statement, name, redirectUris = redirectUris)

  private fun token(pod: PodDbo, assertion: String): TestHttpResponse = didWeb.token(pod, assertion)

  private fun tokenRequest(pod: PodDbo, assertion: String, clientId: String? = null) = didWeb.tokenRequest(pod, assertion, clientId)

  private fun registerEndpoint(pod: PodDbo) = didWeb.registerEndpoint(pod)

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
