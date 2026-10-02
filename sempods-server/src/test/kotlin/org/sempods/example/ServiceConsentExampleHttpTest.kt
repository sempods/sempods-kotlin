package org.sempods.example

import com.google.inject.Inject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.pod.system.auth.DelegatedAccessFlow
import org.sempods.api.pod.system.auth.DelegatedAccessFlow.ConsentPage
import org.sempods.api.pod.system.auth.ServiceAccessFlow
import org.sempods.auth.core.OAuthSyntax
import org.sempods.client.SempodsClientException
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPkce
import org.sempods.client.SempodsPodAuthorization
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsPodServiceClients
import org.sempods.client.SempodsPodTokens
import org.sempods.client.SempodsServiceClientRegistration
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsStatusException
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServiceConsent], the worked example of `sempods-client/docs/client.md` §"Registering a service client",
 * against this pod server. The test is the owner: it opens each page the example sends it to, signed
 * in, submits the form the pod rendered, and follows the redirect back to the example's loopback
 * server. A headless owner does the same from another thread, a little later.
 */
class ServiceConsentExampleHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var services: ServiceAccessFlow

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  // ── On the owner's laptop ───────────────────────────────────────────────────

  @Test
  fun `a program registers a service, the owner confirms in the browser, and it reads what it was given and nothing else`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val browser = OwnerBrowser(owned, selection = setOf("$notes#write"))
    val stored = mutableMapOf<String, String>()
    val example = ServiceConsent(owned.base, client, null)

    val service = example.register("Notes Sync") { id, secret -> stored[id] = secret }
    val access = example.askInBrowser(service.clientId, service.clientSecret, listOf(notes), browser, Duration.ofSeconds(30))

    assertEquals(ServiceConsent.Access.REACHABLE, access)
    assertTrue(service.clientId.startsWith("svc:"), service.clientId)
    assertEquals(mapOf(service.clientId to service.clientSecret), stored)
    assertNotNull(service.activationExpiresAt, "it waited for the owner's consent")
    val opened = browser.opened.single()
    assertEquals("/${owned.pod.name}/_system/auth/service-consent", opened.encodedPath)
    assertEquals(service.clientId, opened.queryParameter("client_id"))
    assertNull(opened.queryParameter("scope"), "the program suggests no rows")
    assertEquals(
      setOf("state", "iss"),
      browser.redirects.single().queryParameterNames,
      "the return carries the decision and its issuer, and nothing else",
    )

    val catalogue = checkNotNull(example.asService(service.clientId, service.clientSecret).contexts().listText().body)
    assertTrue(catalogue.contains(notes), catalogue)
    assertFalse(catalogue.contains(diary), catalogue)
  }

  @Test
  fun `a cancel in the browser is told apart, and the service stands with nothing`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }

    val access = example.askInBrowser(service.clientId, service.clientSecret, listOf(notes), OwnerBrowser(owned, cancel = true), Duration.ofSeconds(30))

    assertEquals(ServiceConsent.Access.CANCELLED, access)
    val refused = assertThrows<SempodsStatusException> { example.asService(service.clientId, service.clientSecret).contexts().listText() }
    assertEquals(400, refused.status)
    assertTrue(refused.bodyExcerpt.contains("invalid_scope"), refused.bodyExcerpt)
  }

  @Test
  fun `a return that names another pod as its issuer is not this consent's answer`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    val mixedUp = OwnerBrowser(owned, selection = setOf("$notes#read")) {
      it.newBuilder().setQueryParameter("iss", "https://elsewhere.example/alice").build()
    }

    assertThrows<SempodsClientException> {
      example.askInBrowser(service.clientId, service.clientSecret, listOf(notes), mixedUp, Duration.ofSeconds(30))
    }
  }

  @Test
  fun `a browser that never returns ends at the time limit`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }

    val access = example.askInBrowser(service.clientId, service.clientSecret, listOf(notes), { }, Duration.ofMillis(500))

    assertEquals(ServiceConsent.Access.TIME_LIMIT, access)
  }

  // ── Headless ────────────────────────────────────────────────────────────────

  @Test
  fun `a service holding one context waits for the next, although its token works before the owner decides`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val d = owned.context("d")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    OwnerBrowser(owned, selection = setOf("$c#read")).open(consentUrl(owned, service))
    assertEquals(200, mintStatus(owned, service.clientId, service.clientSecret), "the token works before the decision about D")

    val owner = LaterOwner(owned, selection = setOf("$c#read", "$d#read"))
    val access = example.askAnywhere(service.clientId, service.clientSecret, listOf(d), owner, Duration.ofSeconds(20))

    assertEquals(ServiceConsent.Access.REACHABLE, access, "reachable means D is listed, which only the owner's decision gives")
    owner.finished()
  }

  @Test
  fun `a cancel on another device ends the wait at its time limit, with nothing granted`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    val owner = LaterOwner(owned, cancel = true)

    val access = example.askAnywhere(service.clientId, service.clientSecret, listOf(c), owner, Duration.ofSeconds(2))

    assertEquals(ServiceConsent.Access.TIME_LIMIT, access)
    owner.finished()
    assertEquals(400, mintStatus(owned, service.clientId, service.clientSecret))
  }

  @Test
  fun `an empty confirmation activates the service, and the wait ends at its time limit`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    val owner = LaterOwner(owned, selection = emptySet())

    val access = example.askAnywhere(service.clientId, service.clientSecret, listOf(c), owner, Duration.ofSeconds(2))

    assertEquals(ServiceConsent.Access.TIME_LIMIT, access)
    owner.finished()
    assertEquals(400, mintStatus(owned, service.clientId, service.clientSecret), "invalid_scope: active, and holding nothing")
    val listed = ServiceConsent(owned.base, client, null).manage(OwnerBrowser(owned)).list().body!!.single()
    assertNull(listed.activationExpiresAt, "active, without a deadline")
  }

  @Test
  fun `another context than the one needed gives a working token, and the wait ends at its time limit`() {
    val owned = ownedPod()
    val d = owned.context("d")
    val e = owned.context("e")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    val owner = LaterOwner(owned, selection = setOf("$e#read"))

    val access = example.askAnywhere(service.clientId, service.clientSecret, listOf(d), owner, Duration.ofSeconds(2))

    assertEquals(ServiceConsent.Access.TIME_LIMIT, access)
    owner.finished()
    assertEquals(200, mintStatus(owned, service.clientId, service.clientSecret))
  }

  @Test
  fun `a registration past its deadline ends the wait as invalid_client`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    services.expire(service.clientId)

    val expired = assertThrows<SempodsStatusException> {
      example.askAnywhere(service.clientId, service.clientSecret, listOf(c), { }, Duration.ofSeconds(20))
    }

    assertEquals(401, expired.status)
    assertTrue(expired.bodyExcerpt.contains("invalid_client"), expired.bodyExcerpt)
  }

  // ── The owner's management ──────────────────────────────────────────────────

  @Test
  fun `the owner lists, narrows, rotates and revokes what was registered`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val example = ServiceConsent(owned.base, client, null)
    val service = example.register("Notes Sync") { _, _ -> }
    example.askInBrowser(
      service.clientId, service.clientSecret, listOf(notes, diary),
      OwnerBrowser(owned, selection = setOf("$notes#read", "$diary#read")), Duration.ofSeconds(30),
    )

    val managing = example.manage(OwnerBrowser(owned))
    val listed = managing.list().body!!.single()
    assertEquals("Notes Sync", listed.clientName)
    assertEquals("registered", listed.origin)
    assertEquals(service.issuedAt, listed.issuedAt)
    assertNotNull(listed.lastUsedAt, "the wait minted a token, which the list shows")
    assertEquals(setOf("$notes#read", "$diary#read"), listed.scopes)

    val narrowed = managing.replaceGrants(service.clientId, listOf("$notes#read"), listed.grantsVersion).body!!
    assertEquals(setOf("$notes#read"), narrowed.scopes)
    val stale = assertThrows<SempodsStatusException> { managing.replaceGrants(service.clientId, emptyList(), listed.grantsVersion) }
    assertEquals(412, stale.status, "the version it read is gone")

    val rotated = managing.rotateSecret(service.clientId).body!!
    assertEquals(401, mintStatus(owned, service.clientId, service.clientSecret), "the old secret stopped at once")
    assertEquals(200, mintStatus(owned, service.clientId, rotated.clientSecret))

    assertTrue(managing.revoke(service.clientId))
    assertEquals(401, mintStatus(owned, service.clientId, rotated.clientSecret), "a revoked service mints nothing")
    assertFalse(managing.revoke(service.clientId))
  }

  @Test
  fun `the owner's tool registers a service active and gives it a context without a dialog`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val example = ServiceConsent(owned.base, client, null)
    val managing = example.manage(OwnerBrowser(owned))

    val service = managing.register("Backup").body!!
    assertNull(service.activationExpiresAt, "the owner's own authority activates it")
    val version = managing.get(service.clientId).body!!.grantsVersion
    assertEquals(setOf("$notes#read"), managing.replaceGrants(service.clientId, listOf("$notes#read"), version).body!!.scopes)

    val catalogue = checkNotNull(example.asService(service.clientId, service.clientSecret).contexts().listText().body)
    assertTrue(catalogue.contains(notes), catalogue)
    assertFalse(catalogue.contains(diary), catalogue)
  }

  @Test
  fun `a program that keeps its manager identifier registers it once across runs`() {
    val owned = ownedPod()
    val manager = SempodsPodAuthorization(SempodsSession(owned.base), client)
      .registerClient("Manager from an earlier run", listOf("http://127.0.0.1/callback")).body!!.clientId
    val browser = OwnerBrowser(owned)

    ServiceConsent(owned.base, client, manager).manage(browser)

    assertEquals(manager, browser.opened.single().queryParameter("client_id"))
  }

  // ── The library pieces the example is made of ───────────────────────────────

  @Test
  fun `a service registers without a credential, each call a service of its own, and reaches no management route`() {
    val owned = ownedPod()
    val registering = SempodsPodServiceClients(SempodsSession(owned.base), client)

    val first = registering.register("Notes Sync").body!!
    val second = registering.register("Notes Sync").body!!

    assertTrue(first.clientId != second.clientId, "a lost answer is not answered again")
    val listing = assertThrows<SempodsStatusException> { registering.list() }
    assertEquals(401, listing.status)
  }

  @Test
  fun `a code is redeemed only with the verifier its challenge was made from`() {
    val owned = ownedPod()
    val (program, code) = approvedCode(owned, OwnerBrowser(owned, followRedirect = false), SempodsPkce.generate())

    val refused = assertThrows<SempodsStatusException> {
      SempodsPodTokens(SempodsSession(owned.base), client).authorizationCode(program, code, REDIRECT, SempodsPkce.generate().verifier)
    }

    assertEquals(400, refused.status)
    assertTrue(refused.bodyExcerpt.contains("invalid_grant"), refused.bodyExcerpt)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val base: SempodsPodBase = SempodsPodBase.of("${SempodsModule.config.apiBaseUrl}${pod.name}")

    fun context(path: String): String {
      val uri = URI("$base/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}$path")
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    return Owned(sempodsTestFactory.newPod(ownerUser = owner), webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private fun consentUrl(owned: Owned, service: SempodsServiceClientRegistration): HttpUrl =
    SempodsPodServiceClients(SempodsSession(owned.base), client).consentUrl(service.clientId, "direct")

  /**
   * The owner's browser, signed in. On a service consent it ticks [selection], or cancels; on an
   * authorization it ticks what was asked for. [tamper] stands for whatever else can reach the
   * program's loopback on the way back.
   */
  private inner class OwnerBrowser(
    private val owned: Owned,
    private val selection: Set<String> = emptySet(),
    private val cancel: Boolean = false,
    private val followRedirect: Boolean = true,
    private val tamper: (HttpUrl) -> HttpUrl = { it },
  ) : ServiceConsent.Browser {

    val opened = mutableListOf<HttpUrl>()
    val redirects = mutableListOf<HttpUrl>()

    override fun open(url: HttpUrl) {
      opened += url
      val cookie = signIn(owned.pod.name, owned.webId).cookie
      val response = http.prepareGet(url.toString()).addHeader("Cookie", cookie).setFollowRedirect(false).execute()
      val submitted = if (url.encodedPath.endsWith("/_system/auth/service-consent")) {
        flow.submit(services.page(response), cookie, scopes = if (cancel) emptySet() else selection, action = "cancel".takeIf { cancel })
      } else {
        flow.submit(ConsentPage.of(response), cookie, scopes = OAuthSyntax.parseScope(url.queryParameter("scope")).toSet())
      }
      val location = submitted.getHeader("Location")
      if (location == null) {
        // A consent without a return address ends on the pod's own page.
        assertEquals(200, submitted.statusCode, submitted.responseBody)
        return
      }
      assertEquals(303, submitted.statusCode, submitted.responseBody)
      val back = tamper(location.toHttpUrl())
      redirects += back
      if (followRedirect) assertEquals(200, http.prepareGet(back.toString()).execute().statusCode)
    }
  }

  /** The owner on another device: opens the URL shown a moment later, from another thread. */
  private inner class LaterOwner(
    private val owned: Owned,
    private val selection: Set<String> = emptySet(),
    private val cancel: Boolean = false,
  ) : ServiceConsent.Owner {

    private var decision: CompletableFuture<Unit>? = null

    override fun show(url: HttpUrl) {
      decision = CompletableFuture.runAsync {
        Thread.sleep(200)
        OwnerBrowser(owned, selection, cancel).open(url)
      }.thenApply { }
    }

    /** Surfaces a failure of the owner's side. */
    fun finished() {
      checkNotNull(decision) { "the example showed no URL" }.get(10, TimeUnit.SECONDS)
    }
  }

  /** A management authority the owner approved, through the library pieces and without the example's loopback: the program and its code. */
  private fun approvedCode(owned: Owned, browser: OwnerBrowser, pkce: SempodsPkce): Pair<String, String> {
    val authorization = SempodsPodAuthorization(SempodsSession(owned.base), client)
    val program = authorization.registerClient("Service manager", listOf(REDIRECT)).body!!.clientId
    browser.open(authorization.authorizationUrl(program, REDIRECT, "service-clients:manage", "s1", pkce))
    return program to authorization.readRedirect(browser.redirects.last().encodedQuery, "s1").code!!
  }

  private fun mintStatus(owned: Owned, clientId: String, secret: String): Int =
    http.preparePost("${owned.base}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(clientId, secret))
      .setBody("grant_type=client_credentials")
      .execute().statusCode

  companion object {

    private val client: OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder()).build()

    @JvmStatic
    @AfterAll
    fun stopClient() {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }

    private const val REDIRECT = "http://127.0.0.1:9/callback"
  }
}
