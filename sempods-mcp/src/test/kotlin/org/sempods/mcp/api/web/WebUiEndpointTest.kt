package org.sempods.mcp.api.web

import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.commons.logging.CapturedLog
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.auth.WebLoginStateStore
import org.sempods.mcp.auth.WebSession
import org.sempods.mcp.auth.JwtTestSupport
import org.sempods.mcp.oauth.FakeIdentityProvider
import org.sempods.mcp.oauth.TokenIssuer
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.persist.AuditEventType
import org.sempods.mcp.persist.AuditLogDao
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import java.util.Date
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfileDao
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.auth.core.SigningKeys
import org.sempods.mcp.persist.oauth.McpSigningKeyStore
import org.sempods.mcp.persist.oauth.SigningKeyDao
import io.ktor.client.request.forms.submitForm
import io.ktor.http.ParametersBuilder
import io.ktor.http.parameters
import org.sempods.mcp.pods.PodConnectStateStore
import org.sempods.mcp.pods.PodOAuthClient
import org.sempods.mcp.pods.PodOAuthMetadata
import org.sempods.mcp.pods.PodUrlPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.verify.VerificationTimes
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the `/_system/ui` session gate: unauthenticated requests are redirected to login,
 * a valid web-session cookie reaches the dashboard. Mongo-backed (the dashboard reads the
 * connection registry); skipped when Mongo is unreachable so the build stays green.
 */
class WebUiEndpointTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val BASE = "https://mcp.test"
    private const val ISSUER = "https://id.test"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)

    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping web-UI test")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop(); mongoClient?.close()
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

  private val config = SempodsMcpConfig(0, MONGO_URL, dbName, BASE, listOf(ISSUER))

  /** The connect-state store wired into the app under test, so a test can seed a [PodConnectStateStore.Pending]. */
  private lateinit var podConnectStateStore: PodConnectStateStore

  /** The audit DAO wired into the app under test, so a test can assert on the emitted trail. */
  private lateinit var auditLogDao: AuditLogDao

  /** The id-server under test, so a test can read back the nonce it must answer with. */
  private val idServer = FakeIdentityProvider(issuer = ISSUER, audience = "did:web:mcp.test")

  private fun ApplicationTestBuilder.installWebUi(): TokenIssuer {
    val database = db!!
    val signingKeys = SigningKeys(McpSigningKeyStore(SigningKeyDao(database, testSecretCipher())))
    val tokenIssuer = TokenIssuer(BASE, signingKeys)
    podConnectStateStore = PodConnectStateStore(database, testSecretCipher())
    auditLogDao = AuditLogDao(database)
    application {
      webUiEndpoint(
        config = config,
        webSession = WebSession(config, tokenIssuer, ServiceBearerVerifier.using(BASE, signingKeys)),
        webLoginStateStore = WebLoginStateStore(database),
        identityProvider = idServer.identityProvider(BASE),
        podOAuthClient = PodOAuthClient(
          SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules)),
          jacksonObjectMapper(), PodUrlPolicy(allowLocal = true),
        ),
        podConnectStateStore = podConnectStateStore,
        podUrlPolicy = PodUrlPolicy(allowLocal = true),
        connectionRegistryDao = ConnectionRegistryDao(database),
        tokenVaultDao = TokenVaultDao(database, testSecretCipher()),
        profileDao = ProfileDao(database),
        auditLog = AuditLog(auditLogDao, retentionDays = 90),
      )
    }
    return tokenIssuer
  }

  @Test
  fun `dashboard without a session redirects to login`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val resp = client.get("/_system/ui")
    assertEquals(HttpStatusCode.Found, resp.status)
    assertEquals("$BASE/_system/ui/login", resp.headers[HttpHeaders.Location])
  }

  @Test
  fun `login starts a PKCE authorization request at the id-server`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val resp = client.get("/_system/ui/login")
    assertEquals(HttpStatusCode.Found, resp.status)
    val location = Url(resp.headers[HttpHeaders.Location]!!)
    assertEquals("$ISSUER/authorize", "${location.protocol.name}://${location.host}${location.encodedPath}")
    assertEquals("code", location.parameters["response_type"], "the implicit flow is gone")
    assertEquals("did:web:mcp.test", location.parameters["client_id"], "this service identifies by its own origin")
    assertEquals("$BASE/_system/ui/login/callback", location.parameters["redirect_uri"])
    assertEquals("S256", location.parameters["code_challenge_method"])
    assertNotNull(location.parameters["code_challenge"], "PKCE is not optional")
    assertNotNull(location.parameters["nonce"], "the token must be bound to this request")
    // Nothing that the token could ride back on: the old flow named its own return address here.
    assertNull(location.parameters["return_to"], "return_to is what let anyone collect the token")
  }

  /**
   * Starts a sign-in and returns what the browser now holds: the authorization request it was sent
   * to, and the login-CSRF pin cookie it must present back.
   */
  private suspend fun io.ktor.client.HttpClient.startUiLogin(): Pair<Url, String> {
    val resp = get("/_system/ui/login")
    val pin = resp.headers.getAll(HttpHeaders.SetCookie)
      ?.first { it.startsWith("mcp_ui_login_") }?.substringBefore(';')
      ?: error("the login redirect must set a browser pin: ${resp.headers.getAll(HttpHeaders.SetCookie)}")
    return Url(resp.headers[HttpHeaders.Location]!!) to pin
  }

  @Test
  fun `the callback exchanges the code and establishes a web session`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val (started, pin) = client.startUiLogin()
    idServer.expect(webId = "https://id.test/e/web-user", nonce = started.parameters["nonce"]!!)

    val resp = client.get("/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=an-auth-code") {
      header(HttpHeaders.Cookie, pin)
    }
    assertEquals(HttpStatusCode.Found, resp.status)
    assertEquals("$BASE/_system/ui", resp.headers[HttpHeaders.Location])
    assertNotNull(resp.headers[HttpHeaders.SetCookie], "a completed login must establish the session cookie")
  }

  @Test
  fun `two sign-ins running at once in one browser both complete`() = testApplication {
    // Two tabs. With one fixed pin cookie name the second redirect overwrites the first pin, the
    // first callback fails *and clears the shared cookie*, and the second then finds nothing
    // either — one concurrent login breaks both. Carried over from the pod server, which had the
    // same shape and where a review found it.
    installWebUi()
    val client = createClient { followRedirects = false }
    val (first, firstPin) = client.startUiLogin()
    val (second, secondPin) = client.startUiLogin()
    val bothPins = "$firstPin; $secondPin"

    suspend fun complete(started: Url): HttpStatusCode {
      idServer.expect(webId = "https://id.test/e/web-user", nonce = started.parameters["nonce"]!!)
      return client.get("/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=c") {
        header(HttpHeaders.Cookie, bothPins)
      }.status
    }

    assertEquals(HttpStatusCode.Found, complete(first), "the first tab must complete")
    assertEquals(HttpStatusCode.Found, complete(second), "and so must the second, which the first must not have disarmed")
  }

  @Test
  fun `a junk state on the callback is refused, not rendered into a cookie name`() = testApplication {
    // `state` is attacker-supplied and reaches a cookie *name*: the pin is withdrawn before the
    // state store is consulted, so that every outcome expires it. Ktor validates cookie names when
    // rendering `Set-Cookie`, so a separator or a space turns the intended 400 into a 500.
    installWebUi()
    val client = createClient { followRedirects = false }

    for (junk in listOf("%20", "a;b", "a=b", "a,b")) {
      val resp = client.get("/_system/ui/login/callback?state=$junk&code=c")
      assertEquals(HttpStatusCode.BadRequest, resp.status, "state='$junk'")
    }
  }

  @Test
  fun `a login started elsewhere cannot be completed in this browser`() = testApplication {
    // Login-CSRF / session fixation. The attacker starts their own sign-in, then gets the callback
    // URL opened in someone else's browser. Without a second factor that browser would come away
    // holding a session for the attacker's identity — and the UI saves pod connections under it.
    installWebUi()
    val attacker = createClient { followRedirects = false }
    val victim = createClient { followRedirects = false }
    val (started, _) = attacker.startUiLogin()
    idServer.expect(webId = "https://id.test/e/the-attacker", nonce = started.parameters["nonce"]!!)
    val callback = "/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=an-auth-code"

    // The victim's browser holds no pin for this flow.
    val resp = victim.get(callback)

    assertEquals(HttpStatusCode.BadRequest, resp.status)
    assertNull(resp.sessionCookie(), "no session may be established for a sign-in this browser did not start")
  }

  @Test
  fun `a callback carrying somebody else's pin is refused`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val (started, _) = client.startUiLogin()
    val (_, otherPin) = client.startUiLogin()
    idServer.expect(webId = "https://id.test/e/web-user", nonce = started.parameters["nonce"]!!)

    // A pin from a different flow is as good as none: the value is compared, not its presence.
    val resp = client.get("/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=c") {
      header(HttpHeaders.Cookie, otherPin)
    }
    assertEquals(HttpStatusCode.BadRequest, resp.status)
  }

  @Test
  fun `a replayed callback finds no pending login`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val (started, pin) = client.startUiLogin()
    idServer.expect(webId = "https://id.test/e/web-user", nonce = started.parameters["nonce"]!!)
    val callback = "/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=c"

    assertEquals(HttpStatusCode.Found, client.get(callback) { header(HttpHeaders.Cookie, pin) }.status)
    // The state is one-time: a captured callback URL replayed later must not mint a second session.
    val replay = client.get(callback) { header(HttpHeaders.Cookie, pin) }
    assertEquals(HttpStatusCode.BadRequest, replay.status)
    assertNull(replay.sessionCookie(), "a rejected callback must not establish a session")
  }

  @Test
  fun `a callback whose token carries the wrong nonce is rejected`() = testApplication {
    installWebUi()
    val client = createClient { followRedirects = false }
    val (started, pin) = client.startUiLogin()
    // The id-server answers with a token minted for a *different* login of the same person — still
    // signed, still unexpired. Only the nonce says it does not belong to this request.
    idServer.expect(webId = "https://id.test/e/web-user", nonce = "some-other-logins-nonce")

    val resp = client.get("/_system/ui/login/callback?state=${enc(started.parameters["state"]!!)}&code=c") {
      header(HttpHeaders.Cookie, pin)
    }
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNull(resp.sessionCookie(), "a token from another login must not establish a session")
  }

  @Test
  fun `a pod that refused the grant is shown as needing a reconnect`() = testApplication {
    // The user-visible half of `PodOAuthException.isDeadGrant`. Without it the dashboard shows a
    // pod that looks connected while every tool call against it quietly returns no token, and the
    // person has no way to learn that reconnecting is what fixes it.
    val user = "https://id.test/e/web-user"
    val tokenIssuer = installWebUi()
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = user, profile = PodKey.DEFAULT_PROFILE, pod = "https://pod.example/p",
        issuer = "https://pod.example/p/_system/auth", podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), deadGrantSince = Date(),
        createdAt = Date(), updatedAt = Date(),
      ),
    )

    val body = createClient { followRedirects = false }.get("/_system/ui") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}")
    }.bodyAsText()

    assertTrue("reconnect needed" in body, "the badge must name the state: $body")
    assertTrue("Re-authorize" in body, "and the action that fixes it must be on the same row")
  }

  @Test
  fun `a healthy pod carries no reconnect marker`() = testApplication {
    // The counter-case, so the badge cannot become decoration that is always on.
    val user = "https://id.test/e/web-user"
    val tokenIssuer = installWebUi()
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = user, profile = PodKey.DEFAULT_PROFILE, pod = "https://pod.example/p",
        issuer = "https://pod.example/p/_system/auth", podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date(),
      ),
    )

    val body = createClient { followRedirects = false }.get("/_system/ui") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}")
    }.bodyAsText()

    assertFalse("reconnect needed" in body, body)
  }

  @Test
  fun `a valid web-session cookie reaches the dashboard`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user")
    val resp = createClient { followRedirects = false }.get("/_system/ui") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie")
    }
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = resp.bodyAsText()
    assertTrue("Your connected pods" in body && "web-user" in body, "dashboard should render for the signed-in user")
    // The default profile shows the suffix-free root MCP URL to paste into the AI client.
    assertTrue("<div class=\"url\">$BASE</div>" in body, "default profile MCP URL should be the service root: $body")
  }

  @Test
  fun `creating a profile switches to it and shows its suffix-free MCP URL`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user2")
    val client = createClient { followRedirects = false }

    // Read the CSRF token off the dashboard, then create a named profile.
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    val created = client.submitForm(
      url = "/_system/ui/profiles/create",
      formParameters = parameters { append("csrf", csrf); append("name", "private") },
    ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    assertEquals(HttpStatusCode.Found, created.status)
    assertEquals("$BASE/_system/ui?profile=private", created.headers[HttpHeaders.Location])

    // The dashboard for that profile advertises the suffix-free named MCP URL.
    val privateDash = client.get("/_system/ui?profile=private") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie")
    }.bodyAsText()
    assertTrue("<div class=\"url\">$BASE/private</div>" in privateDash, privateDash)
    assertTrue("value=\"private\" selected" in privateDash, "switcher should preselect the active profile")
  }

  @Test
  fun `a reserved profile name is rejected`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user3")
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    val resp = client.submitForm(
      url = "/_system/ui/profiles/create",
      formParameters = parameters { append("csrf", csrf); append("name", "token") },
    ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    assertEquals(HttpStatusCode.Found, resp.status)
    assertTrue(resp.headers[HttpHeaders.Location]!!.contains("error="), "reserved name must be refused")
  }

  @Test
  fun `connecting a pod into a non-owned profile is refused, not silently downgraded to default`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user4")
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    // A stale/tampered hidden profile field naming a profile the user never created.
    val resp = client.submitForm(
      url = "/_system/ui/pods/connect",
      formParameters = parameters {
        append("csrf", csrf); append("profile", "ghost"); append("pod_base_url", "https://sempods.org/x")
      },
    ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    assertEquals(HttpStatusCode.Found, resp.status)
    val location = resp.headers[HttpHeaders.Location]!!
    assertTrue("error=" in location && "unknown+profile" in location.replace("%20", "+"),
      "must reject the unknown profile rather than fall back to default: $location")
  }

  @Test
  fun `re-authorizing a pod with no existing connection is refused`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user-reauth")
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    // Re-auth reuses an existing connection's client_id — with nothing connected it must error out
    // (before any pod discovery), not fall through to a fresh DCR.
    val resp = client.submitForm(
      url = "/_system/ui/pods/reauthorize",
      formParameters = parameters {
        append("csrf", csrf); append("profile", PodKey.DEFAULT_PROFILE); append("pod", "https://sempods.org/never-connected")
      },
    ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    assertEquals(HttpStatusCode.Found, resp.status)
    val location = resp.headers[HttpHeaders.Location]!!
    assertTrue("error=" in location && "unknown+connection" in location.replace("%20", "+"),
      "re-auth of a non-connected pod must be refused: $location")
  }

  @Test
  fun `a dead connection re-registers instead of presenting a client_id the pod has forgotten`() = testApplication {
    // Production, 2026-08-21: the pod's DCR rows were dropped, so the stored `dyn:` id no longer
    // resolved there. Re-authorize — the very button the dashboard offers for a pod that needs
    // reconnecting — presented that id anyway, and the pod answered a flat 400 in the browser. The
    // only way out was to disconnect the pod first and connect it again.
    val user = "https://id.test/e/web-user-rereg"
    val tokenIssuer = installWebUi()
    withSimulatedPod(registersAs = "dyn:fresh") { _, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = PodKey.DEFAULT_PROFILE, pod = podBase,
          issuer = authBase, podClientId = "dyn:gone", scopes = setOf("public-read"),
          deadGrantSince = Date(), createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase))

      assertEquals(
        "dyn:fresh", authorize.parameters["client_id"],
        "the re-auth must present the freshly registered id, not the one the pod forgot: $authorize",
      )
    }
  }

  @Test
  fun `a healthy connection keeps its client_id, so the pod still finds its grants`() = testApplication {
    // The counter-case. Pod grants are keyed `(pod, client_id, WebID)`, so re-registering on every
    // re-auth would orphan them on any pod that does not dedup its registrations — which is why the
    // fresh DCR above is reserved for a connection the pod has already declared finished.
    val user = "https://id.test/e/web-user-keepid"
    val tokenIssuer = installWebUi()
    withSimulatedPod(registersAs = "dyn:fresh") { pod, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = PodKey.DEFAULT_PROFILE, pod = podBase,
          issuer = authBase, podClientId = "dyn:stored", scopes = setOf("public-read"),
          createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase))

      assertEquals("dyn:stored", authorize.parameters["client_id"], "$authorize")
      pod.verify(
        request().withMethod("POST").withPath("/p/_system/auth/register"),
        VerificationTimes.never(),
      )
    }
  }

  @Test
  fun `a named profile registers a client of its own, under its own callback and name`() = testApplication {
    // The reason the whole fork exists: a pod resolves context permissions from
    // `(pod, client_id, WebID)` and the access token carries feature scopes only. Two profiles
    // arriving as one client_id are one permission set, so `…/cron-agent` would reach whatever the
    // person allowed in `…/private`. The redirect URI is the fingerprint input with meaning, and
    // the name is what keeps the pod's consent screen from listing two entries that look alike.
    val user = "https://id.test/e/web-user-named-profile"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    withSimulatedPod(registersAs = "dyn:cron") { pod, podBase, _ ->
      val authorize = Url(connect(tokenIssuer, user, podBase, profile = "cron-agent"))

      assertEquals("dyn:cron", authorize.parameters["client_id"])
      assertEquals(
        "$BASE/_system/ui/pods/callback/cron-agent",
        authorize.parameters["redirect_uri"],
        "the profile's own callback is what forks the pod's dedup: $authorize",
      )
      val registration = pod.registrationRequest()
      assertTrue("\"sempods-mcp (cron-agent)\"" in registration, "the profile belongs in the client name: $registration")
      assertTrue("$BASE/_system/ui/pods/callback/cron-agent" in registration, registration)
    }
  }

  @Test
  fun `the default profile registers exactly what it registered before`() = testApplication {
    // No migration, nothing existing breaks: the default profile's identity at every pod it is
    // already connected to has to stay the one it was registered under, or every connection would
    // pay a re-consent for a change it gets nothing from.
    val user = "https://id.test/e/web-user-default-profile"
    val tokenIssuer = installWebUi()
    withSimulatedPod(registersAs = "dyn:root") { pod, podBase, _ ->
      val authorize = Url(connect(tokenIssuer, user, podBase))

      assertEquals("$BASE/_system/ui/pods/callback", authorize.parameters["redirect_uri"], "$authorize")
      val registration = pod.registrationRequest()
      assertTrue("\"sempods-mcp\"" in registration, registration)
      assertFalse("(default)" in registration, "the default profile is unnamed at a pod: $registration")
    }
  }

  @Test
  fun `a pod with no DCR gives a named profile its own did-web identity`() = testApplication {
    // The other registration path, forked by the same segment: `DidWeb.Target.covers` matches on
    // path segments, so `did:web:mcp.test:cron-agent` may receive a code under `/cron-agent/` and
    // nowhere else on the host. Without this a minimal pod would see one static client for every
    // profile — the same failure, on the path that has no registration to vary.
    val user = "https://id.test/e/web-user-didweb-profile"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    withSimulatedPod(registersAs = "unused", publishesAsMetadata = false) { _, podBase, _ ->
      val named = Url(connect(tokenIssuer, user, podBase, profile = "cron-agent"))
      assertEquals("did:web:mcp.test:_system:ui:pods:callback:cron-agent", named.parameters["client_id"], "$named")

      val default = Url(connect(tokenIssuer, user, podBase))
      assertEquals("did:web:mcp.test", default.parameters["client_id"], "$default")
    }
  }

  @Test
  fun `a connection made before the fork keeps the callback its registration is pinned to`() = testApplication {
    // A named profile connected while every profile shared one callback. Re-authorize presents the
    // stored client_id — and a `dyn:` registration lists the address it was registered with, so
    // sending the profile's new callback with it would be refused by the pod. The connection keeps
    // what it has until the person separates it.
    val user = "https://id.test/e/web-user-legacy-callback"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    withSimulatedPod(registersAs = "dyn:fresh") { pod, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = "cron-agent", pod = podBase,
          issuer = authBase, podClientId = "dyn:shared", scopes = setOf("public-read"),
          createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase, profile = "cron-agent"))

      assertEquals("dyn:shared", authorize.parameters["client_id"], "$authorize")
      assertEquals("$BASE/_system/ui/pods/callback", authorize.parameters["redirect_uri"], "$authorize")
      pod.verify(
        request().withMethod("POST").withPath("/p/_system/auth/register"),
        VerificationTimes.never(),
      )
    }
  }

  @Test
  fun `re-authorizing a dead legacy connection keeps the shared identity it was registered under`() = testApplication {
    // The connection most likely to be here, and the one this must not separate. While two
    // profiles share a client at a pod, a sibling's connect retires this one's refresh-token
    // family — so it is flagged dead with its registration perfectly alive, and Re-authorize is
    // the button the dashboard tells the person to press. `reusableClientId` re-registers a dead
    // `dyn:` connection on purpose, and that costs nothing only while the fingerprint is the one
    // the live registration holds: presenting the profile's own callback and name instead would
    // mint a second client_id, drop the pre-checked grants, and do silently what Separate identity
    // exists to ask about.
    val user = "https://id.test/e/web-user-dead-legacy"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    withSimulatedPod(registersAs = "dyn:shared") { pod, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = "cron-agent", pod = podBase,
          issuer = authBase, podClientId = "dyn:shared", scopes = setOf("public-read"),
          deadGrantSince = Date(), createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase, profile = "cron-agent"))

      assertEquals(
        "$BASE/_system/ui/pods/callback",
        authorize.parameters["redirect_uri"],
        "the address the live registration is pinned to, not this profile's: $authorize",
      )
      val registration = pod.registrationRequest()
      assertTrue("\"sempods-mcp\"" in registration, registration)
      assertFalse("(cron-agent)" in registration, "the name is half the fingerprint: $registration")
      assertEquals("dyn:shared", authorize.parameters["client_id"], "so the pod dedups back to it: $authorize")
    }
  }

  @Test
  fun `the dashboard offers to separate a profile still sharing the default client`() = testApplication {
    val user = "https://id.test/e/web-user-separate-offer"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    val cookie = "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}"
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = user, profile = "cron-agent", pod = "https://pod.example/p",
        issuer = "https://pod.example/p/_system/auth", podClientId = "dyn:shared",
        scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date(),
      ),
    )
    // The same pod in the default profile, where the shared client *is* this profile's own.
    ConnectionRegistryDao(db!!).upsert(
      PodConnection(
        user = user, profile = PodKey.DEFAULT_PROFILE, pod = "https://pod.example/p",
        issuer = "https://pod.example/p/_system/auth", podClientId = "dyn:shared",
        scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date(),
      ),
    )
    val client = createClient { followRedirects = false }

    val named = client.get("/_system/ui?profile=cron-agent") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
    assertTrue("shared client" in named, "the profile has to say the pod does not know it apart: $named")
    assertTrue("Separate identity" in named, named)

    val default = client.get("/_system/ui") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
    assertFalse("Separate identity" in default, "the default profile shares nothing — it is the one: $default")
  }

  @Test
  fun `separating a shared connection stores the profile's own client and clears the badge`() = testApplication {
    // The one flow in this change that costs a person a re-consent at the pod, driven end to end:
    // the dashboard's button posts an ordinary connect, the pod registers the profile's own client,
    // and the callback has to write both the new id and the callback it is pinned to. If it kept
    // either, the badge would come back and the person would pay the consent again on the next
    // press — with nothing failing.
    val user = "https://id.test/e/web-user-separated"
    val tokenIssuer = installWebUi()
    ProfileDao(db!!).create(user, "cron-agent")
    val cookie = "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}"
    val client = createClient { followRedirects = false }

    withSimulatedPod(registersAs = "dyn:separated", tokenSubject = user) { _, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = "cron-agent", pod = podBase,
          issuer = authBase, podClientId = "dyn:shared", scopes = setOf("public-read"),
          createdAt = Date(), updatedAt = Date(),
        ),
      )
      assertTrue(
        "Separate identity" in client.get("/_system/ui?profile=cron-agent") {
          header(HttpHeaders.Cookie, cookie)
        }.bodyAsText(),
      )

      // What the button submits.
      val authorize = Url(connect(tokenIssuer, user, podBase, profile = "cron-agent"))
      assertEquals("dyn:separated", authorize.parameters["client_id"], "$authorize")

      // What the pod redirects back to, with the code.
      val callback = client.get(
        "/_system/ui/pods/callback/cron-agent?state=${enc(authorize.parameters["state"]!!)}&code=a-code",
      ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, callback.status)
      assertTrue("error=" !in callback.headers[HttpHeaders.Location]!!, callback.headers[HttpHeaders.Location]!!)

      val stored = assertNotNull(ConnectionRegistryDao(db!!).find(PodKey(user, "cron-agent", podBase)))
      assertEquals("dyn:separated", stored.podClientId, "the profile's own client replaces the shared one")
      assertEquals(
        "$BASE/_system/ui/pods/callback/cron-agent",
        stored.podRedirectUri,
        "and the address it is pinned to, or the next re-authorize sends the wrong one",
      )
      assertFalse(
        "Separate identity" in client.get("/_system/ui?profile=cron-agent") {
          header(HttpHeaders.Cookie, cookie)
        }.bodyAsText(),
        "the offer has to go once it has been taken",
      )
    }
  }

  @Test
  fun `a callback arriving at the wrong profile's address is refused`() = testApplication {
    // The code was issued for one address and is redeemed at that one. A flow that comes back
    // somewhere else is not this flow, whatever `state` it carries.
    val tokenIssuer = installWebUi()
    val user = "https://id.test/e/web-user-callback-mismatch"
    val cookie = "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}"
    val client = createClient { followRedirects = false }
    val state = podConnectStateStore.create { expiresAt ->
      PodConnectStateStore.Pending(
        user = user, profile = "cron-agent", pod = "https://sempods.org/x",
        metadata = PodOAuthMetadata(
          issuer = "https://sempods.org", authorizationEndpoint = "https://sempods.org/a",
          tokenEndpoint = "https://sempods.org/t", registrationEndpoint = null, jwksUri = null,
        ),
        podClientId = "c", codeVerifier = "v",
        redirectUri = "$BASE/_system/ui/pods/callback/cron-agent",
        expiresAt = expiresAt, returnTo = null,
      )
    }

    val resp = client.get("/_system/ui/pods/callback?state=$state&code=some-code") {
      header(HttpHeaders.Cookie, cookie)
    }

    assertEquals(HttpStatusCode.Found, resp.status)
    val location = resp.headers[HttpHeaders.Location]!!
    assertTrue("callback" in location && "mismatch" in location, "$location")
    assertNull(
      ConnectionRegistryDao(db!!).find(PodKey(user, "cron-agent", "https://sempods.org/x")),
      "a refused callback must store nothing",
    )
  }

  @Test
  fun `a pod that advertises offline_access is asked for it, because the connection lives on a refresh token`() = testApplication {
    // This service holds a pod connection open by rotating the pod's refresh token. A pod is free
    // to issue one only to a client that asked for it, so the ask is pinned here rather than left
    // to the pod's current permissiveness: without it, the day a pod stops handing out refresh
    // tokens unasked, every connection would die an hour after it was made and nothing in the flow
    // would say why. Connect and re-authorize share `buildPodAuthorizeRedirect`, so one covers both.
    val user = "https://id.test/e/web-user-offline"
    val tokenIssuer = installWebUi()
    withSimulatedPod(
      registersAs = "dyn:fresh",
      advertisedScopes = listOf("public-read", "offline_access"),
    ) { _, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = PodKey.DEFAULT_PROFILE, pod = podBase,
          issuer = authBase, podClientId = "dyn:stored", scopes = setOf("public-read"),
          createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase))

      assertEquals("offline_access", authorize.parameters["scope"], "$authorize")
    }
  }

  @Test
  fun `a pod that advertises nothing is asked for nothing, not for a scope it may refuse`() = testApplication {
    // The counter-case, and the reason the ask is conditional at all: RFC 6749 §4.1.2.1 lets an
    // authorization server answer `invalid_scope` for a value it does not know. This service
    // connects to pods it does not host, so a scope sent to a pod that never advertised it could
    // end the flow in the browser — losing the connection outright to ask for a refresh token that
    // pod hands out unasked anyway.
    val user = "https://id.test/e/web-user-no-offline"
    val tokenIssuer = installWebUi()
    withSimulatedPod(registersAs = "dyn:fresh") { _, podBase, authBase ->
      ConnectionRegistryDao(db!!).upsert(
        PodConnection(
          user = user, profile = PodKey.DEFAULT_PROFILE, pod = podBase,
          issuer = authBase, podClientId = "dyn:stored", scopes = setOf("public-read"),
          createdAt = Date(), updatedAt = Date(),
        ),
      )

      val authorize = Url(reauthorize(tokenIssuer, user, podBase))

      assertNull(authorize.parameters["scope"], "$authorize")
    }
  }

  @Test
  fun `a pod-denied callback returns to the consent flow (returnTo), keeping the profile`() = testApplication {
    val tokenIssuer = installWebUi()
    val user = "https://id.test/e/web-user6"
    val cookie = tokenIssuer.issueWebSession(user)
    val client = createClient { followRedirects = false }
    // A connect started from the inline consent flow: pending carries the consent-resume returnTo.
    val returnTo = "$BASE/authorize/consent/resume?txn=abc123"
    val state = podConnectStateStore.create { expiresAt ->
      PodConnectStateStore.Pending(
        user = user, profile = "private", pod = "https://sempods.org/x",
        metadata = PodOAuthMetadata(
          issuer = "https://sempods.org", authorizationEndpoint = "https://sempods.org/a",
          tokenEndpoint = "https://sempods.org/t", registrationEndpoint = "https://sempods.org/r", jwksUri = null,
        ),
        podClientId = "c", codeVerifier = "v", redirectUri = "$BASE/_system/ui/pods/callback",
        expiresAt = expiresAt, returnTo = returnTo,
      )
    }
    // The pod denies consent — an OAuth error callback still echoes `state`.
    val resp = client.get("/_system/ui/pods/callback?state=$state&error=access_denied") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie")
    }
    assertEquals(HttpStatusCode.Found, resp.status)
    val location = resp.headers[HttpHeaders.Location]!!
    assertTrue(location.startsWith("$returnTo&"), "pod-denied must return to the consent-resume URL, not the dashboard: $location")
    assertTrue("error=" in location, "the denial must be surfaced back to consent: $location")
  }

  @Test
  fun `a mutation on the default profile is accepted, not rejected as unknown`() = testApplication {
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user5")
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    // The default dashboard renders the `default` sentinel into its hidden profile field; a
    // disconnect (a mutation) submitting it must be accepted, not refused as "unknown profile".
    val resp = client.submitForm(
      url = "/_system/ui/pods/disconnect",
      formParameters = parameters {
        append("csrf", csrf); append("profile", PodKey.DEFAULT_PROFILE); append("pod", "https://sempods.org/x")
      },
    ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    assertEquals(HttpStatusCode.Found, resp.status)
    val location = resp.headers[HttpHeaders.Location]!!
    assertTrue("error=" !in location, "default-profile mutation must not error: $location")
    assertEquals("$BASE/_system/ui?profile=default", location)
    // The disconnect action landed in the audit trail (M6.4).
    val audit = auditLogDao.listFor("https://id.test/e/web-user5", PodKey.DEFAULT_PROFILE)
    assertEquals(1, audit.count { it.type == AuditEventType.POD_DISCONNECT && it.pod == "https://sempods.org/x" })
  }

  @Test
  fun `a pod value on disconnect cannot forge a second log line`() = testApplication {
    // `pod` is a form field, and disconnect is a no-op for a key that names nothing — so nothing
    // between the browser and this line has vouched for it. `docs/logging.md` §"Three rules".
    val tokenIssuer = installWebUi()
    val cookie = tokenIssuer.issueWebSession("https://id.test/e/web-user9")
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui") { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    val forged = "https://sempods.org/x\n2026-01-01 21:00:00,000 WARN  [ktor] pod deleted by admin"

    val lines = CapturedLog.linesFrom("org.sempods.mcp.api.web") {
      client.submitForm(
        url = "/_system/ui/pods/disconnect",
        formParameters = parameters {
          append("csrf", csrf); append("profile", PodKey.DEFAULT_PROFILE); append("pod", forged)
        },
      ) { header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie") }
    }

    val line = lines.single { "pod disconnected" in it }
    assertFalse('\n' in line, "the line carries a raw newline: $line")
    assertTrue("\\u000a" in line, line)
  }

  @Test
  fun `a failed pod token exchange is audited as a connect error`() = testApplication {
    val tokenIssuer = installWebUi()
    val user = "https://id.test/e/web-user7"
    val cookie = tokenIssuer.issueWebSession(user)
    val client = createClient { followRedirects = false }
    // Seed a pending connect whose token endpoint is unreachable — the exchange must fail.
    val state = podConnectStateStore.create { expiresAt ->
      PodConnectStateStore.Pending(
        user = user, profile = "default", pod = "https://sempods.org/x",
        metadata = PodOAuthMetadata(
          issuer = "https://sempods.org", authorizationEndpoint = "https://sempods.org/a",
          tokenEndpoint = "http://127.0.0.1:1/t", registrationEndpoint = null, jwksUri = null,
        ),
        podClientId = "c", codeVerifier = "v", redirectUri = "$BASE/_system/ui/pods/callback",
        expiresAt = expiresAt, returnTo = null,
      )
    }
    val resp = client.get("/_system/ui/pods/callback?state=$state&code=some-code") {
      header(HttpHeaders.Cookie, "${config.sessionCookieName}=$cookie")
    }
    assertEquals(HttpStatusCode.Found, resp.status)
    assertTrue("error=" in resp.headers[HttpHeaders.Location]!!, "the failed exchange must surface an error")
    val audit = auditLogDao.listFor(user)
    assertEquals(1, audit.count {
      it.type == AuditEventType.POD_CONNECT && it.outcome == "error" && it.detail == "connect_failed"
    })
  }

  /**
   * Runs [body] against a MockServer pod that publishes the full RFC 9728 → 8414 chain with a
   * registration endpoint, and answers any DCR with [registersAs] — a pod that has forgotten
   * whatever was registered before and mints a new id.
   */
  private suspend fun withSimulatedPod(
    registersAs: String,
    advertisedScopes: List<String> = emptyList(),
    publishesAsMetadata: Boolean = true,
    /** When set, the pod's `/token` answers a signed access token carrying this `sub`. */
    tokenSubject: String? = null,
    body: suspend (pod: ClientAndServer, podBase: String, authBase: String) -> Unit,
  ) {
    val pod = ClientAndServer.startClientAndServer(0)
    try {
      val podBase = "http://localhost:${pod.port}/p"
      val authBase = "$podBase/_system/auth"
      val scopes = advertisedScopes.joinToString(",") { "\"$it\"" }
        .let { if (it.isEmpty()) "" else ",\"scopes_supported\":[$it]" }
      pod.`when`(request().withMethod("GET").withPath("/p/.well-known/oauth-protected-resource"))
        .respond(
          response().withStatusCode(200)
            .withBody("""{"resource":"$podBase","authorization_servers":["$authBase"]$scopes}"""),
        )
      pod.`when`(request().withMethod("GET").withPath("/p/_system/auth/.well-known/oauth-authorization-server"))
        .respond(
          if (publishesAsMetadata) {
            response().withStatusCode(200).withBody(
              """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize",""" +
                """"token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register"}""",
            )
          } else {
            // The minimal pod: RFC 9728 only, so no DCR to register at and the static client is
            // what this service presents instead.
            response().withStatusCode(404)
          },
        )
      pod.`when`(request().withMethod("POST").withPath("/p/_system/auth/register"))
        .respond(response().withStatusCode(201).withBody("""{"client_id":"$registersAs"}"""))
      if (tokenSubject != null) {
        // Signed, because `verifyAccessTokenSubject` parses the token to read `sub` and a connect
        // whose subject it cannot read fails. This pod advertises no `jwks_uri`, so the signature
        // is trusted by the transport and never checked — any key will do.
        val token = JwtTestSupport.sign(
          JwtTestSupport.generateKey("pod-key"),
          JwtTestSupport.webIdClaims(authBase, tokenSubject),
        )
        pod.`when`(request().withMethod("POST").withPath("/p/_system/auth/token"))
          .respond(
            response().withStatusCode(200).withBody(
              """{"access_token":"$token","token_type":"Bearer","expires_in":3600,"scope":"public-read"}""",
            ),
          )
      }
      body(pod, podBase, authBase)
    } finally {
      pod.stop()
    }
  }

  /** Submits the dashboard's Re-authorize form for [pod] and returns the redirect it answers with. */
  private suspend fun ApplicationTestBuilder.reauthorize(
    tokenIssuer: TokenIssuer,
    user: String,
    pod: String,
    profile: String = PodKey.DEFAULT_PROFILE,
  ): String = submitPodForm(tokenIssuer, user, profile, "reauthorize") { append("pod", pod) }

  /** Submits the dashboard's Connect form for [pod] and returns the redirect it answers with. */
  private suspend fun ApplicationTestBuilder.connect(
    tokenIssuer: TokenIssuer,
    user: String,
    pod: String,
    profile: String = PodKey.DEFAULT_PROFILE,
  ): String = submitPodForm(tokenIssuer, user, profile, "connect") { append("pod_base_url", pod) }

  private suspend fun ApplicationTestBuilder.submitPodForm(
    tokenIssuer: TokenIssuer,
    user: String,
    profile: String,
    action: String,
    fields: ParametersBuilder.() -> Unit,
  ): String {
    val cookie = "${config.sessionCookieName}=${tokenIssuer.issueWebSession(user)}"
    val client = createClient { followRedirects = false }
    val dash = client.get("/_system/ui?profile=$profile") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
    val csrf = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(dash)!!.groupValues[1]
    val resp = client.submitForm(
      url = "/_system/ui/pods/$action",
      formParameters = parameters {
        append("csrf", csrf); append("profile", profile); fields()
      },
    ) { header(HttpHeaders.Cookie, cookie) }
    assertEquals(HttpStatusCode.Found, resp.status)
    return resp.headers[HttpHeaders.Location]!!
  }

  /** The body of the one DCR request [pod] received. */
  private fun ClientAndServer.registrationRequest(): String = assertNotNull(
    retrieveRecordedRequests(request().withMethod("POST").withPath("/p/_system/auth/register")).singleOrNull(),
    "expected exactly one DCR at the pod",
  ).bodyAsString

  private fun enc(v: String) = java.net.URLEncoder.encode(v, Charsets.UTF_8)

  /**
   * The session cookie, if this response establishes one.
   *
   * Every callback also clears the login-CSRF pin, so "no `Set-Cookie` at all" would be the wrong
   * question — what matters is whether anyone was signed in.
   */
  private fun io.ktor.client.statement.HttpResponse.sessionCookie(): String? =
    headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("${config.sessionCookieName}=") }
}
