package org.sempods.mcp.pods

import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.JwtTestSupport
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.TokenVaultDao
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.model.StringBody.subString
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mongo-guarded: exercises the shared [PodTokenProvider] (the read-tool / refresh-sweep seam) for
 * the freshness-cache, on-demand refresh with rotation, and the issuer-pin refusal. A MockServer
 * pod supplies the OAuth discovery + refresh endpoints; if Mongo is absent the class is skipped.
 */
class PodTokenProviderTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB at $MONGO_URL not reachable — skipping")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop()
      mongoClient?.close()
    }

    private fun mongoReachable(): Boolean = runCatching {
      val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(MONGO_URL))
        .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
        .build()
      MongoClients.create(settings).use { it.getDatabase("admin").runCommand(Document("ping", 1)) }
      true
    }.getOrDefault(false)
  }

  private val user = "https://id.test/e/user-1"
  private val profile = PodKey.DEFAULT_PROFILE

  private lateinit var server: ClientAndServer
  private lateinit var pod: String
  private lateinit var authBase: String
  private lateinit var vault: TokenVaultDao
  private lateinit var registry: ConnectionRegistryDao
  private lateinit var provider: PodTokenProvider
  private val auditLog = mockk<AuditLog>(relaxed = true)

  @BeforeEach
  fun each() {
    val database = db!!
    // Fresh collections per test so seeded rows do not bleed across cases.
    database.getCollection(SempodsMcpCollections.POD_TOKENS).drop()
    database.getCollection(SempodsMcpCollections.CONNECTIONS).drop()
    vault = TokenVaultDao(database, testSecretCipher())
    registry = ConnectionRegistryDao(database)
    val oauthTransport = SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules))
    val oauthClient = PodOAuthClient(oauthTransport, jacksonObjectMapper(), PodUrlPolicy(allowLocal = true))
    provider = PodTokenProvider(vault, registry, oauthClient, auditLog)

    server = ClientAndServer.startClientAndServer(0)
    pod = "http://localhost:${server.port}/pod"
    authBase = "$pod/_system/auth"
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$pod","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(200).withBody(
        """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register","jwks_uri":"$authBase/jwks.json"}""",
      ))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(200).withBody("""{"access_token":"at-2","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-2","scope":"public-read"}"""))
  }

  @AfterEach
  fun stop() {
    server.stop()
  }

  private fun seedConnection(
    issuer: String = authBase,
    podRedirectUri: String? = null,
    podSubject: String? = null,
  ) =
    registry.upsert(
      PodConnection(
        user, profile, pod, issuer = issuer, podClientId = "dyn:x", scopes = setOf("public-read"),
        podSubject = podSubject, createdAt = Date(), updatedAt = Date(), podRedirectUri = podRedirectUri,
      ),
    )

  /**
   * A refreshable row: pinned to this pod's own issuer, acting as the service identity, presenting
   * its own registration — the shape most cases here want.
   */
  private fun seedToken(
    expiresAt: Date?,
    refreshToken: String? = "rt-1",
    podClientId: String = "dyn:issued-to",
    podRedirectUri: String = "https://mcp.test/_system/ui/pods/callback",
    issuer: String = authBase,
    podSubject: String = user,
    subjectVerified: Boolean = false,
  ) =
    vault.upsert(
      PodTokens(
        user, profile, pod, accessToken = "at-1", refreshToken = refreshToken,
        accessTokenExpiresAt = expiresAt, updatedAt = Date(), podClientId = podClientId,
        podRedirectUri = podRedirectUri, issuer = issuer, podSubject = podSubject, subjectVerified = subjectVerified,
      ),
    )

  private val key get() = PodKey(user, profile, pod)

  /**
   * Turn the seeded row into the shape this service wrote before the issuer was required: the field
   * is absent from the document, which no constructor can express — so it comes off afterwards.
   */
  private fun dropIssuer() =
    db!!.getCollection(SempodsMcpCollections.POD_TOKENS).updateOne(
      Filters.and(Filters.eq("user", user), Filters.eq("profile", profile), Filters.eq("pod", pod)),
      Updates.unset("issuer"),
    )

  /** Put the connection in the state a pod's `invalid_grant` leaves it in. */
  private fun markDead() = vault.upsert(checkNotNull(vault.find(key)).copy(deadGrantSince = Date()))

  private fun tokenRequests() =
    server.retrieveRecordedRequests(request().withMethod("POST").withPath("/pod/_system/auth/token"))

  /** The form the refresh posted, from the one token request the pod recorded. */
  private fun tokenRequestBody(): String = tokenRequests().single().bodyAsString

  private fun fortyDaysAgo() = Date(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
  private fun thirtyDaysAgo() = Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

  @Test
  fun `a still-fresh token is returned without contacting the pod`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 3_600_000))
    assertEquals("at-1", provider.validAccessToken(key)?.token)
  }

  @Test
  fun `an expiring token is refreshed on demand and the rotation is persisted`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000)) // already past
    assertEquals("at-2", provider.validAccessToken(key)?.token)
    val stored = vault.find(key)!!
    assertEquals("at-2", stored.accessToken)
    assertEquals("rt-2", stored.refreshToken, "the pod rotates the refresh token")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = true) }
  }

  @Test
  fun `a refresh presents the client id the token was issued to, not the registry's`() = runBlocking {
    seedConnection() // the registry says `dyn:x`
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000), podClientId = "dyn:issued-to")

    assertEquals("at-2", provider.validAccessToken(key)?.token)

    val posted = tokenRequestBody()
    assertTrue(posted.contains("client_id=dyn%3Aissued-to"), "the refresh must present the token's own id: $posted")
  }

  @Test
  fun `a token row that predates the required issuer reads as disconnected`() = runBlocking {
    // There is no falling back to the registry here, and that is the point: a reconnect writes the
    // rows independently, so its issuer can name a server that did not mint the family this row
    // still holds — pinning against it would post that family's token to a server it never came
    // from. So the pin is required, and a row without one is unreadable exactly as one whose
    // ciphertext will not open: the person is told to reconnect, and the pod is never asked.
    // A token still an hour from expiry, so this is the pin deciding and not the clock.
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 3_600_000))
    dropIssuer()

    assertNull(vault.find(key), "a row with no pin cannot be mapped")
    assertNull(provider.validAccessToken(key), "and the caller is told to reconnect")
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "the pod is never contacted")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, any(), any()) }
  }

  @Test
  fun `a refresh pins against the issuer that minted the token, not the registry's`() = runBlocking {
    // What a reconnect after an issuer change leaves when only one of the two writes lands: the
    // family in the vault was minted by the pod's current authorization server, and the registry
    // still names the one before it. Pinning against the registry refuses this refresh — and every
    // later one, because nothing but another reconnect moves that row, and reconnecting is exactly
    // what the person has already done.
    seedConnection(issuer = "https://issuer.superseded.example")
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000), issuer = authBase)

    assertEquals("at-2", provider.validAccessToken(key)?.token, "the token's own issuer is what it is pinned to")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, ok = false, detail = "issuer_mismatch") }
  }

  @Test
  fun `a refresh is refused when the pod moved on from the issuer that minted the token`() = runBlocking {
    // The pin itself, sharpened: the registry agrees with the pod's current metadata, and the stored
    // refresh token still belongs to the server that minted it. Posting it to a different one is
    // what the pin exists to stop, so the row it is about is the one that decides — a registry that
    // agrees must not talk this one round.
    seedConnection(issuer = authBase)
    seedToken(
      expiresAt = Date(System.currentTimeMillis() - 60_000),
      issuer = "https://issuer.superseded.example",
    )

    assertNull(provider.validAccessToken(key), "issuer-pin must block posting the refresh token elsewhere")
    assertEquals("at-1", vault.find(key)!!.accessToken, "the stored token is left untouched")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "issuer_mismatch") }
  }

  @Test
  fun `a pod-refused refresh yields null and is audited as a failed refresh`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    // The pod refuses the refresh (revoked/expired refresh token).
    server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(400).withBody("""{"error":"invalid_grant"}"""))
    assertNull(provider.validAccessToken(key), "a refused refresh surfaces as 'reconnect this pod'")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed") }
    // …and the connection carries it, so the dashboard can say so instead of showing a pod that
    // looks healthy while every call to it quietly returns nothing.
    // `assertNotNull` returns its argument, so it must not be the last expression of the block:
    // a @Test method that returns a value is silently skipped by JUnit 5.
    assertNotNull(vault.find(key)?.deadGrantSince)
    Unit
  }

  @Test
  fun `a pod that is merely unwell does not look like a revoked grant`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    // A pod mid-deploy, a proxy, an overloaded server. RFC 6749 §5.2 gives exactly one code that
    // means the grant is finished, and this is not it — so the failure must propagate as an error
    // the caller reports, not be recorded as "reconnect this pod" for a pod nobody disconnected.
    server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(503).withBody("""{"error":"temporarily_unavailable"}"""))

    val failure = assertFailsWith<PodOAuthException> { provider.validAccessToken(key) }

    assertEquals("temporarily_unavailable", failure.oauthErrorCode)
    assertFalse(failure.isDeadGrant)
    assertNull(vault.find(key)?.deadGrantSince, "a pod having a bad minute is not a dead grant")
    assertEquals("rt-1", vault.find(key)!!.refreshToken, "the refresh token is left alone")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed") }
  }

  @Test
  fun `the mark loses to a reconnect that landed while the refresh was in flight`() = runBlocking {
    // The decision is made after a network round trip. A reconnect in that window installed a family
    // the pod never refused, and stamping that one "reconnect needed" would be permanent — nothing
    // clears the mark except another reconnect.
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    assertTrue(vault.tryClaimRefresh(key, "replica-a", Date(System.currentTimeMillis() + 60_000)))

    // What a re-connect leaves behind: a fresh row, and no claim on it.
    vault.upsert(
      PodTokens(user, profile, pod, "at-new", "rt-new", Date(System.currentTimeMillis() + 3_600_000), Date(), issuer = authBase, podSubject = user, subjectVerified = true, podClientId = "dyn:issued-to", podRedirectUri = "https://mcp.test/_system/ui/pods/callback"),
    )
    val marked = vault.markDeadGrantIfClaimedBy(key, at = Date(), holder = "replica-a")

    assertFalse(marked, "the row moved on; this mark must not land")
    assertNull(vault.find(key)?.deadGrantSince)
  }

  @Test
  fun `a dead grant is recorded once and the next refresh never reaches the pod`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    // The wire state a pod deleted and recreated under the same name leaves behind: the pod still
    // exists and answers discovery, it just no longer knows this refresh token.
    server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(400).withBody("""{"error":"invalid_grant"}"""))

    assertNull(provider.validAccessToken(key))
    val markedAt = checkNotNull(vault.find(key)?.deadGrantSince)
    val contacted = server.retrieveRecordedRequests(request()).size

    assertNull(provider.validAccessToken(key), "a grant the pod declared finished stays finished")
    // An unconstrained matcher, so this covers the metadata discovery too, not just the token POST.
    assertEquals(contacted, server.retrieveRecordedRequests(request()).size, "the pod must not be asked a second time")
    // Before the short-circuit every retry re-stamped the mark to "now": a pod dead for eighteen
    // hours read as dead for two minutes.
    assertEquals(markedAt, vault.find(key)?.deadGrantSince, "the mark records when the grant died, not when it was last retried")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed") }
  }

  @Test
  fun `the sweep skips a connection whose grant the pod declared dead`() = runBlocking {
    // The path the production incident actually ran: the row never leaves the sweep's selection,
    // because a refresh that never happens never moves the expiry.
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    markDead()
    val tokens = checkNotNull(vault.find(key))

    provider.refreshIfDue(tokens, RefreshTrigger.Expiring(300))

    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "a dead connection costs the sweep no round trip")
    assertEquals("at-1", checkNotNull(vault.find(key)).accessToken)
    assertEquals("rt-1", checkNotNull(vault.find(key)).refreshToken, "the family is left where it is")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, any(), any()) }
  }

  @Test
  fun `a dead connection never contends for the cross replica refresh claim`() = runBlocking {
    seedConnection()
    // Due (inside the 30s on-demand skew) but NOT yet expired. That is what lets this case tell the
    // chosen short-circuit apart from one sitting behind the claim.
    seedToken(expiresAt = Date(System.currentTimeMillis() + 20_000))
    markDead()
    assertTrue(vault.tryClaimRefresh(key, "replica-a", Date(System.currentTimeMillis() + 60_000)))

    // A caller that reached the claim would lose it, poll for the other replica's result for five
    // seconds, and then hand back the still-unexpired "at-1". Only a check ahead of the claim
    // answers null, and answers it at once.
    assertNull(provider.validAccessToken(key))
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `a claim-losing caller does not hand back a token for a grant the winner found dead`() = runBlocking {
    seedConnection()
    // Due (inside the 30s skew) but NOT expired, so the optimistic fallback below has something to
    // hand back. That is the whole exposure: an already-marked connection is stopped at the entry.
    seedToken(expiresAt = Date(System.currentTimeMillis() + 20_000))
    assertTrue(vault.tryClaimRefresh(key, "replica-a", Date(System.currentTimeMillis() + 60_000)))

    val pending = async(Dispatchers.IO) { provider.validAccessToken(key) }
    // What the claim holder does when the pod refuses: it marks the connection and persists nothing,
    // so the row this caller is polling never moves and it polls to the end of its five-second
    // budget. Marking at 300 ms lands well inside that; the assertion is on the answer, not on the
    // timing, and an early mark would only make this pass at the entry check instead.
    delay(300)
    markDead()

    assertNull(pending.await(), "losing the claim must not turn a dead grant into a usable token")
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `a reconnect clears the mark and the connection refreshes again`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    markDead()
    assertNull(provider.validAccessToken(key))

    // The connect callback's **vault** write, and nothing else. A reconnect writes the vault first
    // and the registry second, and the second write used to be the only thing that cleared the mark
    // — so a half-landed reconnect left a healthy, freshly-minted token behind a mark nothing could
    // lift, and a reconnect is precisely what somebody does once the mark is set. It now defaults
    // back to unset in the same write that installs the new family.
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    assertEquals("at-2", provider.validAccessToken(key)?.token, "a reconnected pod refreshes normally again")
  }

  @Test
  fun `a token row whose connection row is missing is not treated as a dead grant`() = runBlocking {
    // The other fault, and it keeps its own diagnostic: nothing here was ever refused by a pod, so
    // it must not be folded into "this grant is finished".
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    assertNull(provider.validAccessToken(key))
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty(), "there is no connection to discover against")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, any(), any()) }
  }

  @Test
  fun `an unknown connection yields no token`() = runBlocking {
    assertNull(provider.validAccessToken(PodKey(user, profile, "http://localhost:1/none")))
  }

  @Test
  fun `a known-expired token with no refresh token yields null so the caller reconnects`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000), refreshToken = null)
    assertNull(provider.validAccessToken(key), "a provably-expired, un-refreshable token must not be handed out")
  }

  @Test
  fun `a token with unknown expiry is used as-is rather than refresh-churned`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = null)
    assertEquals("at-1", provider.validAccessToken(key)?.token)
  }

  @Test
  fun `the sweep proactively refreshes a token expiring within the window but beyond the on-demand skew`() = runBlocking {
    seedConnection()
    // 200s out: past the 30s on-demand skew, but inside a 300s proactive window.
    seedToken(expiresAt = Date(System.currentTimeMillis() + 200_000))
    val tokens = vault.find(key)!!

    // On-demand does NOT refresh it (still good enough for "now").
    assertEquals("at-1", provider.validAccessToken(key)?.token)

    // The background sweep, using the 300s window, DOES refresh it ahead of expiry.
    provider.refreshIfDue(tokens, RefreshTrigger.Expiring(300))
    assertEquals("at-2", vault.find(key)!!.accessToken, "the sweep must keep the vault warm within its window")
  }

  private fun stubRefreshReturningSubject(webId: String, verified: Boolean = false) {
    server.reset()
    val signingKey = JwtTestSupport.generateKey("pod-refresh-k")
    val metadata = linkedMapOf(
      "issuer" to authBase, "authorization_endpoint" to "$authBase/authorize",
      "token_endpoint" to "$authBase/token", "registration_endpoint" to "$authBase/register",
    )
    if (verified) {
      metadata["jwks_uri"] = "$authBase/jwks.json"
      server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
        .respond(response().withStatusCode(200).withBody(JWKSet(signingKey.toPublicJWK()).toString()))
    }
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$pod","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(200).withBody(
        jacksonObjectMapper().writeValueAsString(metadata),
      ))
    val now = Instant.now()
    val jwt = JwtTestSupport.sign(
      signingKey,
      JWTClaimsSet.Builder().issuer(authBase).subject(webId)
        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build(),
    )
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(200).withBody("""{"access_token":"$jwt","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-2","scope":"public-read"}"""))
  }

  @Test
  fun `refresh is refused when the refreshed token's subject drifts from the recorded identity`() = runBlocking {
    registry.upsert(
      PodConnection(
        user, profile, pod, issuer = authBase, podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original",
        createdAt = Date(), updatedAt = Date(),
      ),
    )
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    stubRefreshReturningSubject("https://pod.example/u/someone-else")
    assertNull(provider.validAccessToken(key), "a refreshed token whose subject drifted must be refused")
    assertEquals("at-1", vault.find(key)!!.accessToken, "the stored token is left untouched on drift")
    assertEquals("https://pod.example/u/original", registry.find(key)!!.podSubject, "the recorded identity is not overwritten")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "identity_drift") }
  }

  @Test
  fun `refresh refuses a refreshed token that fails the pod's advertised JWKS`() = runBlocking {
    // The pod advertises a JWKS (it signs its tokens); on refresh it returns a JWT signed by a
    // DIFFERENT key (tamper/misconfig). verifyAccessTokenSubject fetches the JWKS, the signature
    // fails → VerificationFailed → the refresh must be refused (never persist a rejected token) —
    // even though the sub matches the recorded identity, so it is the verification, not drift, that
    // refuses.
    registry.upsert(
      PodConnection(
        user, profile, pod, issuer = authBase, podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original",
        createdAt = Date(), updatedAt = Date(),
      ),
    )
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$pod","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(200).withBody(
        """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register","jwks_uri":"$authBase/jwks.json"}""",
      ))
    // The pod publishes its real key, but the refresh token is signed by an unrelated key.
    val podKey = JwtTestSupport.generateKey("pod-k")
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/jwks.json"))
      .respond(response().withStatusCode(200).withBody(JWKSet(podKey.toPublicJWK()).toString()))
    val now = Instant.now()
    val forged = JwtTestSupport.sign(
      JwtTestSupport.generateKey("pod-k"),
      JWTClaimsSet.Builder().issuer(authBase).subject("https://pod.example/u/original")
        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).build(),
    )
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(response().withStatusCode(200).withBody("""{"access_token":"$forged","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-2","scope":"public-read"}"""))

    assertNull(provider.validAccessToken(key), "a refreshed token that fails the advertised JWKS must be refused")
    assertEquals("at-1", vault.find(key)!!.accessToken, "the rejected token is not persisted")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "verification_failed") }
  }

  @Test
  fun `refresh keeps a healthy connection alive when the refreshed token's subject is unreadable`() = runBlocking {
    // podSubject recorded, but the refresh grant yields an opaque (non-JWT) access token — or a JWKS
    // blip leaves the subject unreadable. That is NOT identity drift: the rotation must still persist
    // so the connection stays alive, and the recorded identity is left untouched.
    registry.upsert(
      PodConnection(
        user, profile, pod, issuer = authBase, podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original",
        createdAt = Date(), updatedAt = Date(),
      ),
    )
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    // @BeforeEach's refresh stub returns the opaque "at-2" (discovery advertises a jwks_uri, but
    // "at-2" is not a parseable JWT → verifyAccessTokenSubject yields SubjectOutcome.Unreadable →
    // tolerated, not drift).
    assertEquals("at-2", provider.validAccessToken(key)?.token, "an unreadable refreshed subject must not brick the connection")
    assertEquals("rt-2", vault.find(key)!!.refreshToken, "the rotated refresh token is persisted")
    assertEquals("https://pod.example/u/original", registry.find(key)!!.podSubject, "the recorded identity is left untouched")
  }

  @Test
  fun `identity drift is decided against the subject the token was minted for`() = runBlocking {
    // The vault and the registry name different identities. Whichever way round they got there, the
    // refresh is about the family in the vault: it checks drift against that row and rotates it.
    seedConnection(podSubject = "https://pod.example/u/original")
    seedToken(
      expiresAt = Date(System.currentTimeMillis() - 60_000),
      podSubject = "https://pod.example/u/reconnected",
    )
    stubRefreshReturningSubject("https://pod.example/u/reconnected")

    assertNotNull(provider.validAccessToken(key), "the pod answered as the identity this family is for")
    assertEquals("rt-2", vault.find(key)!!.refreshToken, "so the rotation persists")
    assertEquals(
      "https://pod.example/u/original", registry.find(key)!!.podSubject,
      "refresh does not write the registry",
    )
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, ok = false, detail = "identity_drift") }
  }

  @Test
  fun `the identity comes back from the row the token came from`() = runBlocking {
    // A caller cannot resolve this for itself: which row answers is decided inside the call, and a
    // reconnect landing mid-call replaces the row wholesale. So the two travel together, and the
    // registry can describe another family and is not consulted.
    seedConnection(podSubject = "https://pod.example/u/from-a-later-connect")
    seedToken(
      expiresAt = Date(System.currentTimeMillis() + 3_600_000),
      podSubject = "https://pod.example/u/whose-token-this-is",
    )

    val access = assertNotNull(provider.validAccessToken(key))

    assertEquals("at-1", access.token)
    assertEquals("https://pod.example/u/whose-token-this-is", access.podSubject, "the identity this token was minted for")
  }

  @Test
  fun `a refresh that verifies its subject records the evidence with the rotated token`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    stubRefreshReturningSubject(user, verified = true)

    assertNotNull(provider.validAccessToken(key))

    assertTrue(vault.find(key)!!.subjectVerified)
    assertTrue(vault.findFacts(key)!!.subjectVerified)
  }

  @Test
  fun `refresh records verification beside its own identity when the registry names another family`() = runBlocking {
    seedConnection(podSubject = "https://pod.example/u/from-another-connect")
    seedToken(
      expiresAt = Date(System.currentTimeMillis() - 60_000),
      podSubject = "https://pod.example/u/whose-token-this-is", subjectVerified = true,
    )
    stubRefreshReturningSubject("https://pod.example/u/whose-token-this-is")

    assertNotNull(provider.validAccessToken(key))

    val facts = assertNotNull(vault.findFacts(key))
    assertEquals("https://pod.example/u/whose-token-this-is", facts.podSubject)
    assertFalse(facts.subjectVerified, "the no-JWKS refresh must clear the previous verification")
    assertEquals("https://pod.example/u/from-another-connect", registry.find(key)!!.podSubject)
  }

  @Test
  fun `an unreadable refreshed subject leaves the recorded identity where it is`() = runBlocking {
    // Only a subject a refresh actually read may move the reference. An opaque token says nothing
    // about identity, so the row keeps what it had — and a reconnect that rewrote the registry
    // cannot slip its answer in here.
    seedConnection(podSubject = "https://pod.example/u/from-another-connect")
    seedToken(
      expiresAt = Date(System.currentTimeMillis() - 60_000),
      podSubject = "https://pod.example/u/whose-token-this-is", subjectVerified = true,
    )
    // @BeforeEach's stub answers the opaque "at-2": a usable token, no readable identity.

    assertEquals("at-2", provider.validAccessToken(key)?.token, "an unreadable subject must not brick the connection")
    assertEquals(
      "https://pod.example/u/whose-token-this-is", vault.find(key)!!.podSubject,
      "nothing was read, so nothing moved",
    )
    assertFalse(vault.findFacts(key)!!.subjectVerified, "an opaque replacement carries no signature verification")
  }

  @Test
  fun `refresh reports the token identity when the registry records none`() = runBlocking {
    seedConnection() // legacy registry row: podSubject == null
    seedToken(
      expiresAt = Date(System.currentTimeMillis() - 60_000),
      podSubject = "https://pod.example/u/captured",
    )
    stubRefreshReturningSubject("https://pod.example/u/captured")
    assertNotNull(provider.validAccessToken(key), "a legacy connection still refreshes")
    val facts = vault.findFacts(key)!!
    assertEquals("https://pod.example/u/captured", facts.podSubject)
    assertFalse(facts.subjectVerified, "no JWKS means the subject is unverified")
    assertNull(registry.find(key)!!.podSubject, "refresh has no registry repair")
  }

  @Test
  fun `the sweep skips a token that is still outside its window`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 1_000_000)) // far out
    val tokens = vault.find(key)!!
    provider.refreshIfDue(tokens, RefreshTrigger.Expiring(300))
    assertEquals("at-1", vault.find(key)!!.accessToken, "a token beyond the window is left untouched")
  }

  @Test
  fun `two replicas racing the same due token refresh it exactly once (per-token claim)`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    val tokens = vault.find(key)!!

    // Two provider instances = two replicas: separate mutex maps, so only the Mongo claim can
    // serialise them. Both sweep the same due row concurrently.
    val oauthClient = PodOAuthClient(
      SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules)),
      jacksonObjectMapper(), PodUrlPolicy(allowLocal = true),
    )
    val replicaA = PodTokenProvider(vault, registry, oauthClient, auditLog, instanceId = "replica-a")
    val replicaB = PodTokenProvider(vault, registry, oauthClient, auditLog, instanceId = "replica-b")

    listOf(replicaA, replicaB).map { replica ->
      async(Dispatchers.IO) { replica.refreshIfDue(tokens, RefreshTrigger.Expiring(300)) }
    }.awaitAll()

    val tokenRequests = tokenRequests()
    assertEquals(1, tokenRequests.size, "the pod's token endpoint must be hit exactly once — a double refresh trips family-reuse detection")
    val stored = vault.find(key)!!
    assertEquals("at-2", stored.accessToken)
    assertEquals("rt-2", stored.refreshToken)
  }

  @Test
  fun `a re-connect during an in-flight refresh wins, and the stale rotation is discarded`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    // Slow down the pod's token endpoint so the user's re-connect lands mid-refresh.
    server.clear(request().withMethod("POST").withPath("/pod/_system/auth/token"))
    server.`when`(request().withMethod("POST").withPath("/pod/_system/auth/token").withBody(subString("grant_type=refresh_token")))
      .respond(
        response().withStatusCode(200)
          .withBody("""{"access_token":"at-2","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-2","scope":"public-read"}""")
          .withDelay(TimeUnit.MILLISECONDS, 1500),
      )

    val pending = async(Dispatchers.IO) { provider.validAccessToken(key) }
    // Wait until the refresh's token request is actually observed at the pod — MockServer records
    // it on receipt, while the delayed response still holds the refresh in flight. No fixed delay.
    await().atMost(5, TimeUnit.SECONDS).until { tokenRequests().isNotEmpty() }
    // The user re-connects the pod via /_system/ui: a brand-new token family lands in the vault
    // (the upsert clears the refresh claim).
    vault.upsert(
      PodTokens(user, profile, pod, "at-new", "rt-new", Date(System.currentTimeMillis() + 3_600_000), Date(), issuer = authBase, podSubject = user, subjectVerified = true, podClientId = "dyn:issued-to", podRedirectUri = "https://mcp.test/_system/ui/pods/callback"),
    )

    assertEquals("at-new", pending.await()?.token, "the caller must get the re-connect's token, not the stale rotation")
    val stored = vault.find(key)!!
    assertEquals("at-new", stored.accessToken, "the re-connect's tokens must survive the late refresh write")
    assertEquals("rt-new", stored.refreshToken)
    assertTrue(stored.subjectVerified, "the losing opaque refresh cannot clear the replacement family's verification")
  }

  @Test
  fun `the per-key lock map is bounded - a sweep evicts unlocked mutexes but never a held one`() = runBlocking {
    // Hold one mutex across the sweep — it must survive (an in-flight refresh keeps its lock).
    val heldKey = PodKey(user, profile, "http://localhost:9/held")
    val held = provider.lockFor(heldKey)
    assertTrue(held.tryLock())
    try {
      // Touch far more keys than the 4096 cap: the CAS-gated sweep must kick in and evict
      // unlocked mutexes, so the map cannot grow one-entry-per-key-ever-touched (M6.4).
      repeat(5000) { provider.lockFor(PodKey(user, profile, "http://localhost:9/pod-$it")) }
      assertTrue(provider.lockCount < 5000, "the lock map must be swept, not grow unbounded: ${provider.lockCount}")
      assertTrue(provider.lockFor(heldKey) === held, "a held lock must survive the sweep")
    } finally {
      held.unlock()
    }
  }

  @Test
  fun `an on-demand caller that loses the claim polls and reuses the winner's refreshed token`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    // Replica A already holds the claim (it is mid-refresh); replica B's on-demand call must not
    // double-refresh but wait for A's result.
    assertTrue(vault.tryClaimRefresh(key, "replica-a", Date(System.currentTimeMillis() + 60_000)))
    val oauthClient = PodOAuthClient(
      SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules)),
      jacksonObjectMapper(), PodUrlPolicy(allowLocal = true),
    )
    val replicaB = PodTokenProvider(vault, registry, oauthClient, auditLog, instanceId = "replica-b")

    val pending = async(Dispatchers.IO) { replicaB.validAccessToken(key) }
    // Simulate A finishing: persist the refreshed row (the upsert drops A's claim).
    delay(500)
    vault.upsert(
      PodTokens(user, profile, pod, "at-2", "rt-2", Date(System.currentTimeMillis() + 3_600_000), Date(), issuer = authBase, podSubject = user, subjectVerified = true, podClientId = "dyn:issued-to", podRedirectUri = "https://mcp.test/_system/ui/pods/callback"),
    )

    assertEquals("at-2", pending.await()?.token, "the claim-loser must pick up the winner's token")
    val tokenRequests = tokenRequests()
    assertEquals(0, tokenRequests.size, "the claim-loser must never hit the token endpoint")
  }

  @Test
  fun `the preservation tier rotates a family whose access token is nowhere near expiry`() = runBlocking {
    seedConnection()
    // Exactly the row the warm tier can never see: fresh access token, nobody has ever used it.
    vault.upsert(
      PodTokens(
        user, profile, pod, "at-1", "rt-1", Date(System.currentTimeMillis() + 3_600_000), fortyDaysAgo(),
        issuer = authBase, podSubject = user, podClientId = "dyn:issued-to", podRedirectUri = "https://mcp.test/_system/ui/pods/callback",
      ),
    )
    val tokens = vault.find(key)!!

    provider.refreshIfDue(tokens, RefreshTrigger.Preserving(thirtyDaysAgo()))

    val stored = vault.find(key)!!
    assertEquals("rt-2", stored.refreshToken, "the family is what this rotation is for")
    assertEquals("at-2", stored.accessToken)
  }

  @Test
  fun `a preservation sweep does not rotate a family another holder just rotated`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 3_600_000))
    val stale = vault.find(key)!!.copy(updatedAt = fortyDaysAgo())

    // The row on disk has already been rotated (its stamp is now), while this sweeper still holds
    // the snapshot that selected it. Rotating a second time is what trips family-reuse detection.
    provider.refreshIfDue(stale, RefreshTrigger.Preserving(thirtyDaysAgo()))

    assertTrue(
      tokenRequests().isEmpty(),
      "a freshly rotated family must cost no token request",
    )
    assertEquals("at-1", vault.find(key)!!.accessToken)
  }

  @Test
  fun `handing out a token marks the connection used, and refusing one does not`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 3_600_000))
    assertNull(vault.find(key)!!.lastUsedAt, "a seeded row has never been used")

    assertNotNull(provider.validAccessToken(key))
    val marked = vault.find(key)!!.lastUsedAt
    assertNotNull(marked, "a pod read is what 'used' means")

    // Inside the throttle window the marker does not move — one write per connection per minute,
    // not one per tool call, and a read fan-out is one call per pod.
    assertNotNull(provider.validAccessToken(key))
    assertEquals(marked, vault.find(key)!!.lastUsedAt)

    // A connection the caller gets nothing for never reached the pod, so it is not use.
    val unconnected = PodKey(user, profile, "https://other.pod.test/p")
    assertNull(provider.validAccessToken(unconnected))
    assertNull(vault.find(unconnected))
  }
}
