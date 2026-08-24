package org.sempods.mcp.api.oauth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.auth.WebSession
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.mcp.oauth.ConsentTransactionStore
import org.sempods.mcp.oauth.FakeIdentityProvider
import org.sempods.mcp.oauth.LoginStateStore
import org.sempods.mcp.oauth.McpRefreshTokenStore
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.persist.AuditEventType
import org.sempods.mcp.persist.AuditLogDao
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.mcp.persist.oauth.DcrClientDao
import org.sempods.auth.core.SigningKeys
import org.sempods.mcp.persist.oauth.McpSigningKeyStore
import org.sempods.mcp.persist.oauth.SigningKeyDao
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end exercise of the M1 OAuth core: register → authorize → (simulated id-server)
 * callback → consent → token, plus refresh-token rotation and reuse detection. Runs against a
 * real local MongoDB (the repo convention); if Mongo is unreachable the whole class is skipped
 * rather than failed, so the build stays green where Mongo is absent.
 *
 * The id.sempods.org leg is a [FakeIdentityProvider]: a real OIDC round trip — authorization
 * request, code, back-channel token exchange, signed id_token validated against the provider's
 * JWKS — with every document served over the transport port instead of a socket. The browser only
 * ever carries a code, and the consent page carries only an opaque transaction id.
 */
class OAuthFlowIntegrationTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val ISSUER = "https://id.test"
    private const val WEB_ID = "https://id.test/e/user-1"
    private const val BASE = "https://mcp.test"
    private const val REDIRECT = "http://127.0.0.1:51111/cb"

    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)

    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB at $MONGO_URL not reachable — skipping OAuth integration test")
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

  private val mapper = jacksonObjectMapper()

  /** The audit DAO wired into the app under test, so a test can assert on the emitted trail. */
  private lateinit var auditLogDao: AuditLogDao

  /** The id-server under test. `startAuthorize` tells it which nonce the next token must carry. */
  private val idServer = FakeIdentityProvider(issuer = ISSUER, audience = "did:web:mcp.test")

  // PKCE pair for the whole flow.
  private val codeVerifier = "test-verifier-${UUID.randomUUID()}"
  private val codeChallenge: String = run {
    val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
    Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  private fun ApplicationTestBuilder.installAuth() {
    val database = db!!
    val config = SempodsMcpConfig(0, MONGO_URL, dbName, BASE, listOf(ISSUER))
    auditLogDao = AuditLogDao(database)
    val signingKeyDao = SigningKeyDao(database, testSecretCipher())
    // One key set for both: the issuer signs with it and the verifier trusts exactly it, so there
    // is no construction order to get right. WebSession.establish() returns the minted token's
    // CSRF token; verification happens later in
    // ServiceBearerVerifier.verifyWebSession() / WebSession.read().
    val signingKeys = SigningKeys(McpSigningKeyStore(signingKeyDao))
    val tokenIssuer = TokenIssuer(BASE, signingKeys)
    val bearerVerifier = ServiceBearerVerifier.using(BASE, signingKeys)
    application {
      authEndpoint(
        config = config,
        dcrClientDao = DcrClientDao(database),
        authorizationCodeStore = AuthorizationCodeStore(database, SempodsMcpCollections.OAUTH_AUTH_CODES),
        loginStateStore = LoginStateStore(database),
        identityProvider = idServer.identityProvider(BASE),
        consentTransactionStore = ConsentTransactionStore(database),
        refreshTokenStore = McpRefreshTokenStore(database),
        tokenIssuer = tokenIssuer,
        profileDao = ProfileDao(database),
        webSession = WebSession(config, tokenIssuer, bearerVerifier),
        connectionRegistryDao = ConnectionRegistryDao(database),
        tokenVaultDao = TokenVaultDao(database, testSecretCipher()),
        objectMapper = mapper,
        auditLog = AuditLog(auditLogDao, retentionDays = 90),
      )
    }
  }

  @Test
  fun `full authorization-code flow then refresh rotation and reuse detection`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }

    // 1. DCR
    val reg = client.post("/register") {
      contentType(ContentType.Application.Json)
      setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test Client"}""")
    }
    assertEquals(HttpStatusCode.Created, reg.status)
    val clientId = mapper.readTree(reg.bodyAsText())["client_id"].asText()

    // 2. /authorize → 302 to the id-server's OWN authorization endpoint (not a return_to of ours).
    val authResp = client.get(
      "/authorize?response_type=code&client_id=$clientId&redirect_uri=${enc(REDIRECT)}" +
        "&code_challenge=$codeChallenge&code_challenge_method=S256&state=client-state-1",
    )
    assertEquals(HttpStatusCode.Found, authResp.status)
    val loginLocation = Url(authResp.headers[HttpHeaders.Location]!!)
    assertEquals("$ISSUER/authorize", "${loginLocation.protocol.name}://${loginLocation.host}${loginLocation.encodedPath}")
    assertEquals("S256", loginLocation.parameters["code_challenge_method"], "PKCE towards the id-server too")
    val loginState = loginLocation.parameters["state"]!!
    idServer.expect(webId = WEB_ID, nonce = loginLocation.parameters["nonce"]!!)
    // The login redirect sets the login-CSRF nonce cookie; the same browser must present it back.
    val nonceCookie = authResp.headers[HttpHeaders.SetCookie]!!.substringBefore(';')

    // 3. The id-server sends the browser back with a code; the token is fetched over the back
    //    channel with the verifier that never left this process.
    val callbackResp = client.get("/oidc/callback?state=${enc(loginState)}&code=an-auth-code") {
      header(HttpHeaders.Cookie, nonceCookie)
    }
    assertEquals(HttpStatusCode.OK, callbackResp.status)
    val consentHtml = callbackResp.bodyAsText()
    assertTrue(WEB_ID in consentHtml, "consent page should name the signed-in WebID")
    assertTrue(!consentHtml.contains("name=\"token\""), "consent form must not carry the WebID token (P1)")
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]

    // 4. Consent → 302 to the client redirect_uri with code + original state.
    val consentResp = client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
    assertEquals(HttpStatusCode.Found, consentResp.status)
    val cbLocation = Url(consentResp.headers[HttpHeaders.Location]!!)
    assertEquals("client-state-1", cbLocation.parameters["state"])
    val code = cbLocation.parameters["code"]!!

    // 5. Token exchange (authorization_code) with the PKCE verifier.
    val tokenResp = client.submitForm(
      url = "/token",
      formParameters = parameters {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", REDIRECT)
        append("client_id", clientId)
        append("code_verifier", codeVerifier)
      },
    )
    assertEquals(HttpStatusCode.OK, tokenResp.status)
    val tokenJson = mapper.readTree(tokenResp.bodyAsText())
    val accessToken = tokenJson["access_token"].asText()
    val refresh1 = tokenJson["refresh_token"].asText()
    assertEquals(WEB_ID, SignedJWT.parse(accessToken).jwtClaimsSet.subject)

    // 6. A refresh with the wrong client_id is rejected (bound to the issuing client).
    val wrongClient = refresh(client, refresh1, "dyn:someone-else")
    assertEquals(HttpStatusCode.BadRequest, wrongClient.status)
    assertEquals("invalid_grant", mapper.readTree(wrongClient.bodyAsText())["error"].asText())

    // 7. Refresh rotation with the correct client_id → new tokens.
    val refreshResp = refresh(client, refresh1, clientId)
    assertEquals(HttpStatusCode.OK, refreshResp.status)
    val refresh2 = mapper.readTree(refreshResp.bodyAsText())["refresh_token"].asText()
    assertTrue(refresh2 != refresh1, "rotation must mint a new refresh token")

    // 8. Reuse detection: replaying the now-rotated refresh1 fails.
    val reuseResp = refresh(client, refresh1, clientId)
    assertEquals(HttpStatusCode.BadRequest, reuseResp.status)
    assertEquals("invalid_grant", mapper.readTree(reuseResp.bodyAsText())["error"].asText())

    // 9. The audit trail recorded the rotation (7) and the reuse-triggered family revocation (8).
    val audit = auditLogDao.listFor(WEB_ID, PodKey.DEFAULT_PROFILE)
    assertEquals(1, audit.count { it.type == AuditEventType.SERVICE_TOKEN_ROTATE && it.clientId == clientId })
    assertEquals(1, audit.count { it.type == AuditEventType.SERVICE_TOKEN_FAMILY_REVOKED && it.detail == "refresh_reuse" })
  }

  @Test
  fun `the consent screen offers an inline pod-connect and establishes a web session`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()
    val (loginState, nonceCookie) = client.startAuthorize(clientId)
    val callbackResp = client.oidcCallback(loginState, nonceCookie)
    assertEquals(HttpStatusCode.OK, callbackResp.status)
    // A web-session cookie is established for the same verified user (no second login needed for
    // the /_system/ui machinery the inline connect form posts to).
    assertNotNull(callbackResp.headers[HttpHeaders.SetCookie], "consent must establish a web-session cookie")
    val html = callbackResp.bodyAsText()
    // The inline connect form posts to the cookie-protected connect route, carries a CSRF token,
    // and returns to this same consent screen after the pod round-trip.
    assertTrue("/_system/ui/pods/connect" in html, "consent should offer an inline pod-connect form")
    assertTrue("name=\"csrf\"" in html, "the connect form must carry a CSRF token")
    assertTrue("/authorize/consent/resume" in html, "the connect form must return to the consent screen")
    // "Allow" is still present — connecting a pod is skippable.
    assertTrue("action=\"https://mcp.test/authorize/consent\"" in html, "Allow must post to /authorize/consent")
  }

  @Test
  fun `consent resume peeks the transaction so Allow still works afterwards`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()
    val (loginState, nonceCookie) = client.startAuthorize(clientId)
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"")
      .find(client.oidcCallback(loginState, nonceCookie).bodyAsText())!!
      .groupValues[1]

    // The pod callback lands the browser here after a connect. Resume re-renders consent (200) and
    // must NOT consume the txn (it only peeks).
    val resume = client.get("/authorize/consent/resume?txn=${enc(txn)}&connected=${enc("https://sempods.org/p")}")
    assertEquals(HttpStatusCode.OK, resume.status)
    assertTrue(WEB_ID in resume.bodyAsText(), "resumed consent should still name the signed-in WebID")

    // The txn survived the resume: clicking Allow now consumes it and yields an authorization code.
    val consentResp = client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
    assertEquals(HttpStatusCode.Found, consentResp.status)
    assertNotNull(Url(consentResp.headers[HttpHeaders.Location]!!).parameters["code"], "Allow after resume must still mint a code")
  }

  @Test
  fun `the consent screen disconnects a pod, authorized by the transaction (no web-session cookie)`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    // The user already has a pod connected to this profile (seeded directly).
    val pod = "https://sempods.org/alice"
    val key = PodKey(WEB_ID, PodKey.DEFAULT_PROFILE, pod)
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = WEB_ID, profile = PodKey.DEFAULT_PROFILE, pod = pod,
        issuer = "https://sempods.org/_system/auth", podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = null, subjectVerified = false,
        createdAt = Date(), updatedAt = Date(),
      ),
    )

    val (loginState, nonceCookie) = client.startAuthorize(clientId)
    val consentHtml = client.oidcCallback(loginState, nonceCookie).bodyAsText()
    // The consent screen lists the pod with a per-pod Remove control posting to the txn-authorized route.
    assertTrue("/authorize/consent/disconnect" in consentHtml, "consent must offer a per-pod disconnect form")
    assertTrue(pod in consentHtml, "the connected pod must be listed on the consent screen")
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]

    // Disconnect using ONLY the consent txn — no web-session cookie is sent (the point: it rides on
    // the txn, not the /_system/ui cookie) → 302 back to the resume screen flagging the removed pod.
    val disc = client.submitForm(
      url = "/authorize/consent/disconnect",
      formParameters = parameters { append("txn", txn); append("pod", pod) },
    )
    assertEquals(HttpStatusCode.Found, disc.status)
    val loc = Url(disc.headers[HttpHeaders.Location]!!)
    assertEquals("/authorize/consent/resume", loc.encodedPath)
    assertEquals(pod, loc.parameters["disconnected"], "the redirect must flag which pod was removed")

    // The connection row is gone, and the removal is audited.
    assertEquals(null, ConnectionRegistryDao(db!!).find(key), "disconnect must delete the connection row")
    val audit = auditLogDao.listFor(WEB_ID, PodKey.DEFAULT_PROFILE)
    assertEquals(1, audit.count { it.type == AuditEventType.POD_DISCONNECT && it.pod == pod })

    // The txn is only peeked (not consumed): clicking Allow afterwards still mints a code.
    val allow = client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
    assertEquals(HttpStatusCode.Found, allow.status)
    assertNotNull(Url(allow.headers[HttpHeaders.Location]!!).parameters["code"], "Allow after a disconnect must still mint a code")
  }

  @Test
  fun `consent disconnect with an expired transaction is rejected`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    // A random (never-issued) txn stands in for an expired/unknown one: touch() finds nothing.
    val resp = client.submitForm(
      url = "/authorize/consent/disconnect",
      formParameters = parameters { append("txn", "no-such-txn"); append("pod", "https://sempods.org/alice") },
    )
    assertEquals(HttpStatusCode.BadRequest, resp.status, "an unknown/expired txn must not disconnect anything")
  }

  @Test
  fun `a named-profile flow mints an access token carrying that profile claim`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }

    // DCR + authorize + token all under the `/private` profile path.
    val clientId = mapper.readTree(
      client.post("/private/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Profile Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    val (loginState, nonceCookie) = client.startAuthorize(clientId, profile = "private")
    val consentHtml = client.oidcCallback(loginState, nonceCookie).bodyAsText()
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]
    val code = Url(
      client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
        .headers[HttpHeaders.Location]!!,
    ).parameters["code"]!!

    val tokenResp = client.submitForm(
      url = "/private/token",
      formParameters = parameters {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", REDIRECT)
        append("client_id", clientId)
        append("code_verifier", codeVerifier)
      },
    )
    assertEquals(HttpStatusCode.OK, tokenResp.status)
    val accessToken = mapper.readTree(tokenResp.bodyAsText())["access_token"].asText()
    val claims = SignedJWT.parse(accessToken).jwtClaimsSet
    assertEquals(WEB_ID, claims.subject)
    assertEquals("private", claims.getStringClaim("profile"), "the access token must carry the named profile")
    // The token's issuer must equal the profile's advertised AS issuer (RFC 8414 / 9068), not the
    // service root — otherwise a client that binds validation to the discovered issuer rejects it.
    assertEquals("$BASE/private", claims.issuer, "named-profile token iss must match the profile's metadata issuer")
    // Authorizing an AI client against a named profile materialises it (no pre-creation needed):
    // the profile is now a first-class record, so the web-UI switcher and pod-connect see it.
    assertTrue(ProfileDao(db!!).exists(WEB_ID, "private"), "first authorization must auto-create the named profile")
  }

  @Test
  fun `a code issued for a named profile cannot be redeemed at another profile's token endpoint`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/private/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"X"}""")
      }.bodyAsText(),
    )["client_id"].asText()
    val (loginState, nonceCookie) = client.startAuthorize(clientId, profile = "private")
    val consentHtml = client.oidcCallback(loginState, nonceCookie).bodyAsText()
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]
    val code = Url(
      client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
        .headers[HttpHeaders.Location]!!,
    ).parameters["code"]!!

    // Redeeming the `/private` code at the ROOT token endpoint (default profile) must fail.
    val resp = client.submitForm(
      url = "/token",
      formParameters = parameters {
        append("grant_type", "authorization_code")
        append("code", code); append("redirect_uri", REDIRECT)
        append("client_id", clientId); append("code_verifier", codeVerifier)
      },
    )
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertEquals("invalid_grant", mapper.readTree(resp.bodyAsText())["error"].asText())
  }

  @Test
  fun `a reserved or malformed profile segment is rejected with 404`() = testApplication {
    installAuth()
    // `token` is a reserved root route, so it can never be a profile.
    assertEquals(
      HttpStatusCode.NotFound,
      client.post("/token/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"x"}""")
      }.status,
    )
  }

  @Test
  fun `authorization-code exchange with wrong PKCE verifier is rejected`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    val (loginState, nonceCookie) = client.startAuthorize(clientId)
    val consentHtml = client.oidcCallback(loginState, nonceCookie).bodyAsText()
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]
    val code = Url(
      client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
        .headers[HttpHeaders.Location]!!,
    ).parameters["code"]!!

    val resp = client.submitForm(
      url = "/token",
      formParameters = parameters {
        append("grant_type", "authorization_code")
        append("code", code)
        append("redirect_uri", REDIRECT)
        append("client_id", clientId)
        append("code_verifier", "the-wrong-verifier")
      },
    )
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertEquals("invalid_grant", mapper.readTree(resp.bodyAsText())["error"].asText())
  }

  @Test
  fun `DCR dedup echoes the current request redirect uri not the stored one`() = testApplication {
    installAuth()
    fun regBody(port: Int) = """{"redirect_uris":["http://127.0.0.1:$port/cb"],"client_name":"Loopback Client"}"""
    val first = mapper.readTree(
      client.post("/register") { contentType(ContentType.Application.Json); setBody(regBody(51111)) }.bodyAsText(),
    )
    // Same logical client restarts on a new ephemeral port → dedup to the same client_id.
    val second = mapper.readTree(
      client.post("/register") { contentType(ContentType.Application.Json); setBody(regBody(62222)) }.bodyAsText(),
    )
    assertEquals(first["client_id"].asText(), second["client_id"].asText(), "loopback re-register must dedup")
    // The response must echo the CURRENT request's port, not the stored first one.
    assertEquals("http://127.0.0.1:62222/cb", second["redirect_uris"][0].asText())
  }

  @Test
  fun `register rejects a non-loopback http redirect uri`() = testApplication {
    installAuth()
    val resp = client.post("/register") {
      contentType(ContentType.Application.Json)
      setBody("""{"redirect_uris":["http://evil.example.com/cb"],"client_name":"Bad"}""")
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertEquals("invalid_redirect_uri", mapper.readTree(resp.bodyAsText())["error"].asText())
  }

  @Test
  fun `an unknown client or an unregistered redirect uri is answered directly, never by redirect`() = testApplication {
    // The property A7 turned from "true by construction" into "true by type": nothing may travel
    // to a redirect address before that address is known to belong to the client that named it —
    // an error included. It used to be the ordering inside doAuthorize that held it; a
    // `Redirectable` holds it now. Either way the observable answer is a 400 with no Location.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Honest Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    fun authorize(id: String, redirect: String) =
      "/authorize?response_type=code&client_id=${enc(id)}&redirect_uri=${enc(redirect)}" +
        "&code_challenge=$codeChallenge&code_challenge_method=S256&state=s"

    // An unknown client: the address may look plausible, but nothing may be sent to it.
    val unknownClient = http.get(authorize("dyn:nobody", REDIRECT))
    assertEquals(HttpStatusCode.BadRequest, unknownClient.status)
    assertNull(unknownClient.headers[HttpHeaders.Location], "an unknown client must not receive a redirect")

    // A known client naming an address that is not its own — the open-redirector shape.
    val foreignRedirect = http.get(authorize(clientId, "https://evil.example/cb"))
    assertEquals(HttpStatusCode.BadRequest, foreignRedirect.status)
    assertNull(foreignRedirect.headers[HttpHeaders.Location], "an unregistered address must not receive a redirect")
  }

  @Test
  fun `an unsupported response_type reaches a proven client, but never an unproven one`() = testApplication {
    // RFC 6749 §4.1.2.1 places this code at the redirect_uri. It is still ordered AFTER the
    // address check, so the wrong response_type on an unknown client stays a direct answer —
    // the ordering rule holds for every error, not only for the ones it was written for.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Honest Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    fun authorize(id: String) =
      "/authorize?response_type=token&client_id=${enc(id)}&redirect_uri=${enc(REDIRECT)}" +
        "&code_challenge=$codeChallenge&code_challenge_method=S256&state=s"

    val proven = Url(assertNotNull(http.get(authorize(clientId)).headers[HttpHeaders.Location]))
    assertEquals("unsupported_response_type", proven.parameters["error"])

    val unproven = http.get(authorize("dyn:nobody"))
    assertEquals(HttpStatusCode.BadRequest, unproven.status)
    assertNull(unproven.headers[HttpHeaders.Location], "an unknown client is told nothing, by any route")
  }

  @Test
  fun `a padded client id does not start a flow it cannot finish`() = testApplication {
    // `redirectTargetFor` trims before it validates, so a padded id passes the check while the raw
    // one is what gets parked — and the raw one matches no row at the callback. The flow would
    // start, cost the user an id-server round trip, and die on the way back. Worse, the parked
    // value reaches log lines with its control characters intact.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Padded Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    val padded = "%0A" + enc(clientId) + "%0A"
    val resp = http.get(
      "/authorize?response_type=code&client_id=$padded&redirect_uri=${enc(REDIRECT)}" +
        "&code_challenge=$codeChallenge&code_challenge_method=S256&state=s",
    )
    // Either answer is defensible; what is not is starting the flow with a value that will not
    // resolve later. Assert on the invariant: whatever is parked must match what was validated.
    assertEquals(HttpStatusCode.Found, resp.status, "the padded id resolves to its client, so the flow starts")
    val parked = db!!.getCollection(SempodsMcpCollections.OAUTH_LOGIN_STATES).find().first()
    assertEquals(clientId, parked?.getString("clientId"), "the parked id must be the one that was validated")
  }

  @Test
  fun `a blank client state is dropped rather than crashing the redirect`() = testApplication {
    // RFC 6749 makes `state` opaque VSCHAR, so `state=%20` is a syntactically valid request — and
    // nimbus's `State` refuses a blank value from its constructor. A client that sends one must
    // still get its error at its own address; a state that carries no information is simply not
    // echoed, which is the one part of the answer it cannot use anyway.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Blank State Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    // No PKCE → an error that travels to the proven address, carrying the blank state.
    val resp = http.get("/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT)}&state=%20")
    val location = Url(assertNotNull(resp.headers[HttpHeaders.Location], "the client must still be answered"))
    assertEquals("invalid_request", location.parameters["error"])
    assertNull(location.parameters["state"], "a blank state is not echoed")
  }

  @Test
  fun `a client deleted mid-flow is answered directly, not at its former callback address`() = testApplication {
    // The one behaviour change A7 makes on purpose. The callback used to redirect its refusals
    // straight to the parked `redirect_uri`; now it re-proves the address, which means a client
    // that vanished between /authorize and the callback gets a direct 400 instead. That is the
    // honest answer for a registration that no longer exists — and the rule the type enforces
    // does not carve out an exception for an address that was proven a few seconds ago.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Vanishing Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()
    val (loginState, nonceCookie) = http.startAuthorize(clientId)

    db!!.getCollection(SempodsMcpCollections.OAUTH_CLIENT_REGISTRATIONS).deleteOne(Document("clientId", clientId))

    val resp = http.get("/oidc/callback?state=${enc(loginState)}&error=access_denied") {
      header(HttpHeaders.Cookie, nonceCookie)
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertNull(resp.headers[HttpHeaders.Location], "a deleted client has no address left to be answered at")
  }

  @Test
  fun `an id-server refusal reaches the client as access_denied without the foreign error text`() = testApplication {
    // The id-server's own `error` value is a stranger's string that would otherwise land in an AI
    // client's URL bar verbatim. The client gets the code it can act on; the raw value is logged.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Honest Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()
    val (loginState, nonceCookie) = http.startAuthorize(clientId, state = "client-state-1")

    val resp = http.get("/oidc/callback?state=${enc(loginState)}&error=totally_made_up_code") {
      header(HttpHeaders.Cookie, nonceCookie)
    }
    val location = Url(assertNotNull(resp.headers[HttpHeaders.Location], "a live client is answered at its address"))
    assertEquals("access_denied", location.parameters["error"])
    assertEquals("client-state-1", location.parameters["state"])
    assertTrue(
      location.parameters["error_description"]?.contains("totally_made_up_code") != true,
      "the id-server's raw error must not travel to the client: ${location.parameters["error_description"]}",
    )
  }

  @Test
  fun `a proven redirect address still receives its errors by redirect`() = testApplication {
    // The counter-property of the test above: once the client and its address are established,
    // RFC 6749 §4.1.2.1 wants the error AT the redirect address, so A7 must not have collapsed
    // these into direct responses along with the two cases above.
    installAuth()
    val http = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      http.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Honest Client"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    // PKCE is mandatory here, and the failure is reported to the client's own address.
    val missingPkce = http.get("/authorize?response_type=code&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT)}&state=s")
    val location = Url(assertNotNull(missingPkce.headers[HttpHeaders.Location], "a proven address receives the error"))
    assertEquals("invalid_request", location.parameters["error"])
    assertEquals("s", location.parameters["state"], "the client's state must come back with the error")
  }

  @Test
  fun `a junk state on the login callback is refused, not rendered into a cookie name`() = testApplication {
    // The AI-client callback withdraws its pin before consulting the state store, so that every
    // outcome expires it — which puts attacker-supplied text in a cookie *name*. Ktor validates
    // names when rendering `Set-Cookie`, so a separator or a space would turn this 400 into a 500.
    installAuth()
    val client = createClient { followRedirects = false }

    for (junk in listOf("%20", "a;b", "a=b", "a,b")) {
      assertEquals(
        HttpStatusCode.BadRequest,
        client.get("/oidc/callback?state=$junk&code=an-auth-code").status,
        "state='$junk'",
      )
    }
  }

  @Test
  fun `the login callback is rejected without a matching browser nonce cookie`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    // A victim's browser that never started /authorize holds no nonce cookie: completing a captured
    // login URL there must be refused, not turned into a consent for the initiating (attacker's) client.
    val (state1, _) = client.startAuthorize(clientId)
    val noCookie = client.get("/oidc/callback?state=${enc(state1)}&code=an-auth-code")
    assertEquals(HttpStatusCode.BadRequest, noCookie.status, "callback without the nonce cookie must be refused")

    // A wrong nonce is likewise refused.
    val (state2, _) = client.startAuthorize(clientId)
    val wrongCookie = client.get("/oidc/callback?state=${enc(state2)}&code=an-auth-code") {
      header(HttpHeaders.Cookie, "mcp_login_$state2=not-the-nonce")
    }
    assertEquals(HttpStatusCode.BadRequest, wrongCookie.status, "callback with a mismatched nonce must be refused")

    // The matching cookie (same browser) completes the flow — control.
    val (state3, nonce3) = client.startAuthorize(clientId)
    assertEquals(HttpStatusCode.OK, client.oidcCallback(state3, nonce3).status, "the initiating browser's nonce must be accepted")
  }

  @Test
  fun `consent requires explicit confirmation when a connected pod runs a foreign identity`() = testApplication {
    installAuth()
    val client = createClient { followRedirects = false }
    val clientId = mapper.readTree(
      client.post("/register") {
        contentType(ContentType.Application.Json)
        setBody("""{"redirect_uris":["$REDIRECT"],"client_name":"Test"}""")
      }.bodyAsText(),
    )["client_id"].asText()

    // The user already connected a pod that authorized them under the pod's OWN identity (foreign).
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = WEB_ID, profile = PodKey.DEFAULT_PROFILE, pod = "https://pod.example",
        issuer = "https://pod.example/_system/auth", podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = "https://pod.example/u/42", subjectVerified = false,
        createdAt = Date(), updatedAt = Date(),
      ),
    )

    val (loginState, nonceCookie) = client.startAuthorize(clientId)
    val consentHtml = client.oidcCallback(loginState, nonceCookie).bodyAsText()
    assertTrue("confirm_foreign" in consentHtml, "consent must render a foreign-identity confirmation checkbox")
    val txn = Regex("name=\"txn\" value=\"([^\"]+)\"").find(consentHtml)!!.groupValues[1]

    // Allow WITHOUT the confirmation → re-renders (200), issues no code. The txn is only peeked, so it survives.
    val denied = client.submitForm(url = "/authorize/consent", formParameters = parameters { append("txn", txn) })
    assertEquals(HttpStatusCode.OK, denied.status, "un-confirmed foreign consent must re-render, not issue a code")
    assertTrue("confirm_foreign" in denied.bodyAsText(), "the re-render must still offer the confirmation")

    // Allow WITH the confirmation → 302 to the client redirect_uri with a code.
    val allowed = client.submitForm(
      url = "/authorize/consent",
      formParameters = parameters { append("txn", txn); append("confirm_foreign", "on") },
    )
    assertEquals(HttpStatusCode.Found, allowed.status)
    assertNotNull(Url(allowed.headers[HttpHeaders.Location]!!).parameters["code"], "confirmed foreign consent must mint a code")
  }

  private suspend fun refresh(client: io.ktor.client.HttpClient, refreshToken: String, clientId: String): HttpResponse =
    client.submitForm(
      url = "/token",
      formParameters = parameters {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
        append("client_id", clientId)
      },
    )

  private fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)

  /** Drives `/authorize`, returning the opaque login state and the login-CSRF nonce cookie the same
   *  browser must present back at `/oidc/callback` (the test client does not carry cookies itself). */
  /**
   * Drives `/authorize` to the point where the browser sits at the id-server, and arms the fake
   * id-server with the nonce this particular flow will insist on.
   *
   * @return the flow's `state` (also the key it is stored under) and the login-CSRF cookie pair.
   */
  private suspend fun HttpClient.startAuthorize(clientId: String, profile: String = "", state: String = "s"): Pair<String, String> {
    val prefix = if (profile.isEmpty()) "" else "/$profile"
    val resp = get(
      "$prefix/authorize?response_type=code&client_id=$clientId&redirect_uri=${enc(REDIRECT)}" +
        "&code_challenge=$codeChallenge&code_challenge_method=S256&state=$state",
    )
    val authorizationRequest = Url(resp.headers[HttpHeaders.Location]!!)
    idServer.expect(webId = WEB_ID, nonce = authorizationRequest.parameters["nonce"]!!)
    // Set-Cookie "mcp_login_<state>=<value>; Path=/oidc; …" → the bare pair for the Cookie header.
    val nonceCookie = resp.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
    return authorizationRequest.parameters["state"]!! to nonceCookie
  }

  /** Completes the id-server round-trip at `/oidc/callback`, presenting the browser nonce cookie. */
  private suspend fun HttpClient.oidcCallback(loginState: String, nonceCookie: String): HttpResponse =
    get("/oidc/callback?state=${enc(loginState)}&code=an-auth-code") {
      header(HttpHeaders.Cookie, nonceCookie)
    }
}
