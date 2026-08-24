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

  private fun seedConnection(issuer: String = authBase) =
    registry.upsert(PodConnection(user, profile, pod, issuer = issuer, podClientId = "dyn:x", scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date()))

  private fun seedToken(expiresAt: Date?, refreshToken: String? = "rt-1") =
    vault.upsert(PodTokens(user, profile, pod, accessToken = "at-1", refreshToken = refreshToken, accessTokenExpiresAt = expiresAt, updatedAt = Date()))

  private val key get() = PodKey(user, profile, pod)

  private fun fortyDaysAgo() = Date(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
  private fun thirtyDaysAgo() = Date(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

  @Test
  fun `a still-fresh token is returned without contacting the pod`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() + 3_600_000))
    assertEquals("at-1", provider.validAccessToken(key))
  }

  @Test
  fun `an expiring token is refreshed on demand and the rotation is persisted`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000)) // already past
    assertEquals("at-2", provider.validAccessToken(key))
    val stored = vault.find(key)!!
    assertEquals("at-2", stored.accessToken)
    assertEquals("rt-2", stored.refreshToken, "the pod rotates the refresh token")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = true) }
  }

  @Test
  fun `a refresh is refused when the pod's issuer no longer matches the connected issuer`() = runBlocking {
    seedConnection(issuer = "https://issuer.changed.example")
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
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
    assertNotNull(registry.find(key)?.deadGrantSince)
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
    assertNull(registry.find(key)?.deadGrantSince, "a pod having a bad minute is not a dead grant")
    assertEquals("rt-1", vault.find(key)!!.refreshToken, "the refresh token is left alone")
    verify(exactly = 0) { auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed") }
  }

  @Test
  fun `the mark loses to a reconnect that landed while the refresh was in flight`() = runBlocking {
    // The decision is made after a network round trip. Without the compare-and-set, a reconnect in
    // that window would be stamped "reconnect needed" permanently — nothing clears the mark except
    // another reconnect.
    seedConnection()
    val stale = checkNotNull(registry.find(key)).updatedAt

    registry.upsert(checkNotNull(registry.find(key)).copy(updatedAt = Date()))
    val marked = registry.markDeadGrant(key, at = Date(), ifUpdatedAt = stale)

    assertFalse(marked, "the row moved on; this update must not land")
    assertNull(registry.find(key)?.deadGrantSince)
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
    val markedAt = checkNotNull(registry.find(key)?.deadGrantSince)
    val contacted = server.retrieveRecordedRequests(request()).size

    assertNull(provider.validAccessToken(key), "a grant the pod declared finished stays finished")
    // An unconstrained matcher, so this covers the metadata discovery too, not just the token POST.
    assertEquals(contacted, server.retrieveRecordedRequests(request()).size, "the pod must not be asked a second time")
    // `markDeadGrant` compare-and-sets on `updatedAt` and sets it as well, so before the
    // short-circuit every retry matched the row its own predecessor had written and re-stamped the
    // mark to "now": a pod dead for eighteen hours read as dead for two minutes.
    assertEquals(markedAt, registry.find(key)?.deadGrantSince, "the mark records when the grant died, not when it was last retried")
    verify(exactly = 1) { auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed") }
  }

  @Test
  fun `the sweep skips a connection whose grant the pod declared dead`() = runBlocking {
    // The path the production incident actually ran: the row never leaves the sweep's selection,
    // because a refresh that never happens never moves the expiry.
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    registry.upsert(checkNotNull(registry.find(key)).copy(deadGrantSince = Date(), updatedAt = Date()))
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
    registry.upsert(checkNotNull(registry.find(key)).copy(deadGrantSince = Date(), updatedAt = Date()))
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
    registry.upsert(checkNotNull(registry.find(key)).copy(deadGrantSince = Date(), updatedAt = Date()))

    assertNull(pending.await(), "losing the claim must not turn a dead grant into a usable token")
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `a reconnect clears the mark and the connection refreshes again`() = runBlocking {
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    registry.upsert(checkNotNull(registry.find(key)).copy(deadGrantSince = Date(), updatedAt = Date()))
    assertNull(provider.validAccessToken(key))

    // What a re-authorize through `/_system/ui` leaves behind: a fresh registry row (the mark
    // defaults back to unset) and a fresh token family. Since the short-circuit this is the ONLY
    // exit from the dead state, so it has to keep working.
    seedConnection()
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))

    assertEquals("at-2", provider.validAccessToken(key), "a reconnected pod refreshes normally again")
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
    assertEquals("at-1", provider.validAccessToken(key))
  }

  @Test
  fun `the sweep proactively refreshes a token expiring within the window but beyond the on-demand skew`() = runBlocking {
    seedConnection()
    // 200s out: past the 30s on-demand skew, but inside a 300s proactive window.
    seedToken(expiresAt = Date(System.currentTimeMillis() + 200_000))
    val tokens = vault.find(key)!!

    // On-demand does NOT refresh it (still good enough for "now").
    assertEquals("at-1", provider.validAccessToken(key))

    // The background sweep, using the 300s window, DOES refresh it ahead of expiry.
    provider.refreshIfDue(tokens, RefreshTrigger.Expiring(300))
    assertEquals("at-2", vault.find(key)!!.accessToken, "the sweep must keep the vault warm within its window")
  }

  /** Re-stub discovery WITHOUT a jwks_uri and a refresh that returns a JWT access token with [webId]
   *  as its subject, so [PodOAuthClient.verifyAccessTokenSubject] reads it via the trusted (TLS)
   *  no-JWKS path without needing a matching signing key. */
  private fun stubRefreshReturningSubject(webId: String) {
    server.reset()
    server.`when`(request().withMethod("GET").withPath("/pod/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$pod","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/pod/_system/auth/.well-known/oauth-authorization-server"))
      .respond(response().withStatusCode(200).withBody(
        """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register"}""",
      ))
    val now = Instant.now()
    val jwt = JwtTestSupport.sign(
      JwtTestSupport.generateKey("pod-refresh-k"),
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
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original", subjectVerified = false,
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
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original", subjectVerified = true,
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
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/original", subjectVerified = false,
        createdAt = Date(), updatedAt = Date(),
      ),
    )
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    // @BeforeEach's refresh stub returns the opaque "at-2" (discovery advertises a jwks_uri, but
    // "at-2" is not a parseable JWT → verifyAccessTokenSubject yields SubjectOutcome.Unreadable →
    // tolerated, not drift).
    assertEquals("at-2", provider.validAccessToken(key), "an unreadable refreshed subject must not brick the connection")
    assertEquals("rt-2", vault.find(key)!!.refreshToken, "the rotated refresh token is persisted")
    assertEquals("https://pod.example/u/original", registry.find(key)!!.podSubject, "the recorded identity is left untouched")
  }

  @Test
  fun `refresh backfills a legacy connection's missing pod subject`() = runBlocking {
    seedConnection() // legacy row: podSubject == null
    seedToken(expiresAt = Date(System.currentTimeMillis() - 60_000))
    stubRefreshReturningSubject("https://pod.example/u/captured")
    assertNotNull(provider.validAccessToken(key), "a legacy connection still refreshes")
    val conn = registry.find(key)!!
    assertEquals("https://pod.example/u/captured", conn.podSubject, "a legacy null subject is backfilled on refresh")
    assertFalse(conn.subjectVerified, "no JWKS → the backfilled subject is unverified")
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

    val tokenRequests = server.retrieveRecordedRequests(
      request().withMethod("POST").withPath("/pod/_system/auth/token"),
    )
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
    await().atMost(5, TimeUnit.SECONDS).until {
      server.retrieveRecordedRequests(request().withMethod("POST").withPath("/pod/_system/auth/token")).isNotEmpty()
    }
    // The user re-connects the pod via /_system/ui: a brand-new token family lands in the vault
    // (the upsert clears the refresh claim).
    vault.upsert(PodTokens(user, profile, pod, "at-new", "rt-new", Date(System.currentTimeMillis() + 3_600_000), Date()))

    assertEquals("at-new", pending.await(), "the caller must get the re-connect's token, not the stale rotation")
    val stored = vault.find(key)!!
    assertEquals("at-new", stored.accessToken, "the re-connect's tokens must survive the late refresh write")
    assertEquals("rt-new", stored.refreshToken)
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
    vault.upsert(PodTokens(user, profile, pod, "at-2", "rt-2", Date(System.currentTimeMillis() + 3_600_000), Date()))

    assertEquals("at-2", pending.await(), "the claim-loser must pick up the winner's token")
    val tokenRequests = server.retrieveRecordedRequests(
      request().withMethod("POST").withPath("/pod/_system/auth/token"),
    )
    assertEquals(0, tokenRequests.size, "the claim-loser must never hit the token endpoint")
  }

  @Test
  fun `the preservation tier rotates a family whose access token is nowhere near expiry`() = runBlocking {
    seedConnection()
    // Exactly the row the warm tier can never see: fresh access token, nobody has ever used it.
    vault.upsert(
      PodTokens(user, profile, pod, "at-1", "rt-1", Date(System.currentTimeMillis() + 3_600_000), fortyDaysAgo()),
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
      server.retrieveRecordedRequests(request().withMethod("POST").withPath("/pod/_system/auth/token")).isEmpty(),
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
