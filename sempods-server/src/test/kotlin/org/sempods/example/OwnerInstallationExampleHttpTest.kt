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
import org.sempods.auth.core.OAuthSyntax
import org.sempods.client.SempodsOkHttp
import org.sempods.client.SempodsPkce
import org.sempods.client.SempodsPodAuthorization
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsPodServiceClients
import org.sempods.client.SempodsPodTokens
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsStatusException
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [OwnerInstallation], the worked example of `docs/pod-client.md` §"Registering a service client",
 * against this pod server. The test is the owner's browser: it opens each page the example sends it
 * to, signed in as the owner, submits the form the pod rendered, and follows the redirect back to the
 * example's loopback server.
 */
class OwnerInstallationExampleHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  // ── The example ─────────────────────────────────────────────────────────────

  @Test
  fun `a program registers a service, the owner grants it, and it reads what it was granted and nothing else`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val browser = OwnerBrowser(owned)
    val stored = mutableMapOf<String, String>()

    val installation = OwnerInstallation(owned.base, client, browser, null).install("Notes Sync", listOf("$notes#write")) { id, secret ->
      stored[id] = secret
    }

    val service = installation.service()
    assertTrue(service.clientId.startsWith("svc:"), service.clientId)
    assertEquals(mapOf(service.clientId to service.clientSecret), stored)
    assertNull(service.secretExpiresAt)
    assertNotNull(service.activationExpiresAt, "it waits for the owner's consent")
    assertTrue(installation.grants().isGranted)
    assertEquals(setOf("$notes#write"), installation.grants().scopes)
    assertEquals(listOf("/_system/auth/grant"), browser.opened.map { it.encodedPath.removePrefix("/${owned.pod.name}") })
    assertTrue(browser.opened.single().queryParameter("client_id")!!.startsWith("dyn:"))

    val catalogue = checkNotNull(OwnerInstallation(owned.base, client, browser, null).asService(service.clientId, service.clientSecret).contexts().listText().body)
    assertTrue(catalogue.contains(notes), catalogue)
    assertFalse(catalogue.contains(diary), catalogue)
  }

  @Test
  fun `a refused grant consent is reported apart from a registration that stands`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val example = OwnerInstallation(owned.base, client, OwnerBrowser(owned, grant = false), null)

    val installation = example.install("Notes Sync", listOf("$notes#read")) { _, _ -> }

    assertFalse(installation.grants().isGranted)
    assertEquals("access_denied", installation.grants().error)
    val service = installation.service()
    val refused = assertThrows<SempodsStatusException> { example.asService(service.clientId, service.clientSecret).contexts().listText() }
    assertEquals(400, refused.status)
    assertTrue(refused.bodyExcerpt.contains("invalid_scope"), refused.bodyExcerpt)

    val listed = OwnerInstallation(owned.base, client, OwnerBrowser(owned), null).manage().list().body!!.single()
    assertEquals(service.clientId, listed.clientId)
    assertTrue(listed.scopes.isEmpty())
  }

  @Test
  fun `a grant consent that does not finish leaves the registration standing and says why`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val stored = mutableMapOf<String, String>()
    val closedAtTheGrant = OwnerInstallation.Browser { url ->
      if (url.encodedPath.endsWith("/_system/auth/grant")) throw java.io.IOException("the owner closed the browser")
      OwnerBrowser(owned).open(url)
    }

    val installation = OwnerInstallation(owned.base, client, closedAtTheGrant, null).install("Notes Sync", listOf("$notes#read")) { id, secret ->
      stored[id] = secret
    }

    assertNull(installation.grants())
    assertEquals("the owner closed the browser", installation.grantsUnfinished().message)
    assertEquals(mapOf(installation.service().clientId to installation.service().clientSecret), stored)
    val listed = OwnerInstallation(owned.base, client, OwnerBrowser(owned), null).manage().list().body!!.single()
    assertEquals(installation.service().clientId, listed.clientId)
    assertTrue(listed.scopes.isEmpty())
  }

  @Test
  fun `a program that keeps its own identifier registers it once across runs`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    // Registered under another name than the example's, so its own registration would answer another identifier.
    val installer = SempodsPodAuthorization(SempodsSession(owned.base), client)
      .registerClient("Installer from an earlier run", listOf("http://127.0.0.1/callback")).body!!.clientId
    val browser = OwnerBrowser(owned)

    OwnerInstallation(owned.base, client, browser, installer).install("Importer", listOf("$notes#read")) { _, _ -> }

    assertEquals(installer, browser.opened.single().queryParameter("client_id"))
  }

  @Test
  fun `a registration that asks for no contexts opens no browser, and waits for the owner`() {
    val owned = ownedPod()
    val browser = OwnerBrowser(owned)

    val installation = OwnerInstallation(owned.base, client, browser, null).install("Importer", emptyList()) { _, _ -> }

    assertNull(installation.grants())
    assertNull(installation.grantsUnfinished())
    assertTrue(browser.opened.isEmpty(), "no consent was needed to register")
    val listed = OwnerInstallation(owned.base, client, OwnerBrowser(owned), null).manage().list().body!!.single()
    assertEquals(installation.service().activationExpiresAt, listed.activationExpiresAt, "the owner sees its deadline")
  }

  @Test
  fun `the owner lists, narrows, rotates and revokes what was registered`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val example = OwnerInstallation(owned.base, client, OwnerBrowser(owned), null)
    val service = example.install("Notes Sync", listOf("$notes#read", "$diary#read")) { _, _ -> }.service()
    example.asService(service.clientId, service.clientSecret).contexts().listText()

    val managing = example.manage()
    val listed = managing.list().body!!.single()
    assertEquals("Notes Sync", listed.clientName)
    assertEquals("installed", listed.origin)
    assertEquals(service.issuedAt, listed.issuedAt)
    assertNotNull(listed.lastUsedAt, "the service minted a token, which the list shows")
    assertEquals(setOf("$notes#read", "$diary#read"), listed.scopes)

    assertEquals(setOf("$notes#read"), managing.removeGrants(service.clientId, listOf("$diary#read")).body!!.scopes)

    val rotated = managing.rotateSecret(service.clientId).body!!
    assertEquals(401, mintStatus(owned, service.clientId, service.clientSecret), "the old secret stopped at once")
    assertEquals(200, mintStatus(owned, service.clientId, rotated.clientSecret))

    assertTrue(managing.revoke(service.clientId))
    assertEquals(401, mintStatus(owned, service.clientId, rotated.clientSecret), "a revoked service mints nothing")
    assertFalse(managing.revoke(service.clientId))
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
    val (installer, code) = approvedCode(owned, OwnerBrowser(owned, followRedirect = false), SempodsPkce.generate())

    val refused = assertThrows<SempodsStatusException> {
      SempodsPodTokens(SempodsSession(owned.base), client).authorizationCode(installer, code, REDIRECT, SempodsPkce.generate().verifier)
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

  /**
   * The owner's browser, signed in. It submits the form each page renders: ticking what the page was
   * asked for when [approve] (and [grant], for the grant consent), ticking nothing otherwise.
   */
  private inner class OwnerBrowser(
    private val owned: Owned,
    private val approve: Boolean = true,
    private val grant: Boolean = true,
    private val followRedirect: Boolean = true,
  ) : OwnerInstallation.Browser {

    val opened = mutableListOf<HttpUrl>()
    val redirects = mutableListOf<HttpUrl>()

    override fun open(url: HttpUrl) {
      opened += url
      val cookie = signIn(owned.pod.name, owned.webId).cookie
      val page = http.prepareGet(url.toString()).addHeader("Cookie", cookie).setFollowRedirect(false).execute()
      assertEquals(200, page.statusCode, page.responseBody)
      val isGrant = url.encodedPath.endsWith("/_system/auth/grant")
      val tick = if (isGrant) grant else approve
      val fields = hiddenFields(page.responseBody).toMutableList()
      if (isGrant) fields += "action" to if (grant) "grant" else "refuse"
      if (tick) OAuthSyntax.parseScope(url.queryParameter("scope")).forEach { fields += "scope" to it }
      val action = url.resolve(unescapeHtml(FORM_ACTION.find(page.responseBody)!!.groupValues[1]))!!
      val submitted = http.preparePost(action.toString())
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .addHeader("Cookie", cookie)
        .setBody(fields.joinToString("&") { (name, value) -> "${enc(name)}=${enc(value)}" })
        .setFollowRedirect(false).execute()
      assertEquals(303, submitted.statusCode, submitted.responseBody)
      val back = submitted.getHeader("Location")!!.toHttpUrl()
      redirects += back
      if (followRedirect) assertEquals(200, http.prepareGet(back.toString()).execute().statusCode)
    }
  }

  /** A management authority the owner approved, through the library pieces and without the example's loopback: the program and its code. */
  private fun approvedCode(owned: Owned, browser: OwnerBrowser, pkce: SempodsPkce): Pair<String, String> {
    val authorization = SempodsPodAuthorization(SempodsSession(owned.base), client)
    val program = authorization.registerClient("Service setup", listOf(REDIRECT)).body!!.clientId
    browser.open(authorization.authorizationUrl(program, REDIRECT, "service-clients:manage", "s1", pkce))
    return program to authorization.readRedirect(browser.redirects.last().encodedQuery, "s1").code!!
  }

  private fun mintStatus(owned: Owned, clientId: String, secret: String): Int =
    http.preparePost("${owned.base}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(clientId, secret))
      .setBody("grant_type=client_credentials")
      .execute().statusCode

  /** The hidden fields a browser submits: none inside a `<template>`, which is inert until a script clones it. */
  private fun hiddenFields(page: String): List<Pair<String, String>> =
    HIDDEN.findAll(page.replace(TEMPLATE, "")).map { it.groupValues[1] to unescapeHtml(it.groupValues[2]) }.toList()

  companion object {

    private val client: OkHttpClient = SempodsOkHttp.install(OkHttpClient.Builder()).build()

    @JvmStatic
    @AfterAll
    fun stopClient() {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }

    private const val REDIRECT = "http://127.0.0.1:9/callback"
    private val HIDDEN = Regex("""<input type="hidden" name="([^"]+)" value="([^"]*)"""")
    private val TEMPLATE = Regex("""<template[\s\S]*?</template>""")
    private val FORM_ACTION = Regex("""<form[^>]*\saction="([^"]*)"""")
  }
}
