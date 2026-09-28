package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.pod.system.auth.ServiceAccessFlow.Service
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.podId
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Service access end to end: a registered service sends the owner to its consent, the owner decides
 * in the shared dialog, and the service learns the result from the token endpoint and
 * `GET /contexts` (`docs/auth/service-clients.md` §"Consent").
 *
 * Every grant comes from the rendered dialog posted back. What the SDK's wait makes of these answers
 * is `ServiceConsentExampleHttpTest`'s.
 */
class ServiceConsentHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var services: ServiceAccessFlow

  @Inject
  private lateinit var flow: DelegatedAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var serviceClientStore: PodServiceClientStore

  private val loopback = "http://127.0.0.1/callback"

  // ── Access ──────────────────────────────────────────────────────────────────

  @Test
  fun `a read grant reads its context, writes nothing into it, and sees no other`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val d = owned.context("d")
    val existing = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(c))
    val hidden = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(d))
    val service = services.register(owned.pod)
    confirm(owned, service, setOf("$c#read"))
    val token = services.accessToken(owned.pod, service)

    assertEquals(listOf(c), services.contexts(owned.pod, token))
    assertEquals(200, read(existing, token).statusCode, "what the context held before the service existed")
    val write = writeNote(c, owned, token)
    assertEquals(403, write.statusCode, write.responseBody)
    assertEquals(404, read(hidden, token).statusCode, "a context it was not granted reads as absent")
  }

  @Test
  fun `a manage grant creates and writes below its root, and nowhere beside it`() {
    val owned = ownedPod()
    val root = owned.context("r")
    owned.context("r-other")
    val service = services.register(owned.pod)
    confirm(owned, service, setOf("$root#manage"))
    val token = services.accessToken(owned.pod, service)

    val created = createContext(owned, "r/x", token)
    assertEquals(201, created.statusCode, created.responseBody)
    val written = writeNote("$root/x", owned, token)
    assertEquals(201, written.statusCode, written.responseBody)

    assertEquals(403, createContext(owned, "r-other/x", token).statusCode)
    assertEquals(403, writeNote(owned.contextIri("r-other"), owned, token).statusCode)
  }

  @Test
  fun `a narrowed selection applies to the next request of a token already held`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val d = owned.context("d")
    val service = services.register(owned.pod)
    confirm(owned, service, setOf("$c#read", "$d#read"))
    val token = services.accessToken(owned.pod, service)
    assertEquals(setOf(c, d), services.contexts(owned.pod, token).toSet())

    confirm(owned, service, setOf("$c#read"))

    assertEquals(listOf(c), services.contexts(owned.pod, token))
  }

  // ── Outcomes ────────────────────────────────────────────────────────────────

  @Test
  fun `a confirmation with a return address echoes state and carries no grant`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie, redirectUri = "http://127.0.0.1:53124/callback"))
    val confirmed = flow.submit(page, owned.cookie, scopes = setOf("$c#read"))

    assertEquals(303, confirmed.statusCode, confirmed.responseBody)
    assertEquals("http://127.0.0.1:53124/callback?state=consent-1", confirmed.getHeader("Location"))
    assertEquals(setOf("$c#read"), stored(owned, service).scopes)
  }

  @Test
  fun `a cancel answers access_denied and changes nothing`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie, redirectUri = loopback))
    val cancelled = flow.submit(page, owned.cookie, scopes = setOf("$c#read"), action = "cancel")

    assertEquals(303, cancelled.statusCode, cancelled.responseBody)
    val back = flow.query(cancelled)
    assertEquals("access_denied", back["error"])
    assertEquals("consent-1", back["state"])
    val row = stored(owned, service)
    assertEquals(emptySet(), row.scopes)
    assertNotNull(row.pendingUntil, "a cancel activates nothing")
  }

  @Test
  fun `an empty confirmation activates the service without grants`() {
    val owned = ownedPod()
    owned.context("c")
    val service = services.register(owned.pod)

    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    val confirmed = flow.submit(page, owned.cookie, scopes = emptySet())

    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    val row = stored(owned, service)
    assertNull(row.pendingUntil, "active, with no deadline")
    assertEquals(emptySet(), row.scopes)
    assertEquals("invalid_scope", services.tokenError(services.token(owned.pod, service)))
  }

  @Test
  fun `without a return address the pod shows the finish page`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    val confirmed = flow.submit(services.page(services.open(owned.pod, service.clientId, owned.cookie)), owned.cookie, scopes = setOf("$c#read"))
    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    assertNull(confirmed.getHeader("Location"))
    assertTrue("go back to the program" in confirmed.responseBody, confirmed.responseBody)

    val cancelled = flow.submit(services.page(services.open(owned.pod, service.clientId, owned.cookie)), owned.cookie, action = "cancel")
    assertEquals(200, cancelled.statusCode, cancelled.responseBody)
    assertTrue("Nothing changed" in cancelled.responseBody, cancelled.responseBody)
  }

  // ── The consent URL ─────────────────────────────────────────────────────────

  @Test
  fun `an unknown, a delegated or an operator-provisioned identifier is answered 400 without a redirect`() {
    val owned = ownedPod()
    val app = flow.register(owned.pod, redirectUri = loopback)
    serviceClientStore.register(owned.pod.hosted, "backend", emptySet(), label = "backend")

    for (clientId in listOf("svc:nobody", app.clientId, "backend", "")) {
      val answer = services.open(owned.pod, clientId, owned.cookie, redirectUri = loopback)
      assertEquals(400, answer.statusCode, "$clientId: ${answer.responseBody}")
      assertTrue(answer.getHeader("Location").isNullOrBlank(), clientId)
    }
  }

  @Test
  fun `a return address the service did not register is answered 400 without a redirect`() {
    val owned = ownedPod()
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    for (redirect in listOf("https://evil.example/cb", "http://127.0.0.1/other", "not a uri")) {
      val answer = services.open(owned.pod, service.clientId, owned.cookie, redirectUri = redirect)
      assertEquals(400, answer.statusCode, "$redirect: ${answer.responseBody}")
      assertTrue(answer.getHeader("Location").isNullOrBlank(), redirect)
    }
    assertEquals(400, services.open(owned.pod, services.register(owned.pod).clientId, owned.cookie, redirectUri = loopback).statusCode)
  }

  @Test
  fun `a consent opened without a session resumes after the sign-in`() {
    val owned = ownedPod()
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    val parked = services.open(owned.pod, service.clientId, cookie = null, redirectUri = loopback)
    assertEquals(307, parked.statusCode, parked.responseBody)

    val resumed = http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", service.clientId)
      .addQueryParam("redirect_uri", loopback)
      .addQueryParam("state", "consent-1")
      .executeSignedInAs(owned.webId)
    assertEquals(200, resumed.statusCode, resumed.responseBody)
    assertTrue("serviceConsentForm" in resumed.responseBody, "the callback resumes the service consent, not /authorize")
  }

  @Test
  fun `only the pod owner decides`() {
    val owned = ownedPod()
    val service = services.register(owned.pod, redirectUris = listOf(loopback))

    val stranger = services.open(owned.pod, service.clientId, signIn(owned.pod.name, "https://id.test/stranger").cookie, loopback)

    assertEquals(403, stranger.statusCode, stranger.responseBody)
    assertTrue(stranger.getHeader("Location").isNullOrBlank())
  }

  @Test
  fun `an owner signed in under a linked alias confirms`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val cookie = signIn(owned.pod.name, "https://id.test/oidc/${randomId()}", alsoKnownAs = listOf(owned.webId)).cookie

    val confirmed = flow.submit(services.page(services.open(owned.pod, service.clientId, cookie)), cookie, scopes = setOf("$c#read"))

    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    assertEquals(listOf(c), services.contexts(owned.pod, services.accessToken(owned.pod, service)))
  }

  @Test
  fun `the grant route is gone`() {
    val owned = ownedPod()

    val grant = http.prepareGet("${podBase(owned.pod)}/_system/auth/grant")
      .addHeader("Cookie", owned.cookie)
      .setFollowRedirect(false).execute()

    assertEquals(404, grant.statusCode, grant.responseBody)
  }

  // ── What the token endpoint tells the service ───────────────────────────────

  @Test
  fun `a pending service is refused a token, is served once confirmed, and an expired one is unknown`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val lapsing = services.register(owned.pod)

    assertEquals("invalid_scope", services.tokenError(services.token(owned.pod, service)))
    confirm(owned, service, setOf("$c#read"))
    assertNull(services.tokenError(services.token(owned.pod, service)))

    services.expire(lapsing.clientId)
    val expired = services.token(owned.pod, lapsing)
    assertEquals(401, expired.statusCode, expired.responseBody)
    assertEquals("invalid_client", services.tokenError(expired))
  }

  @Test
  fun `a late confirmation does not revive a registration past its deadline`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    services.expire(service.clientId)

    val late = flow.submit(page, owned.cookie, scopes = setOf("$c#read"))

    assertEquals(404, late.statusCode, late.responseBody)
    assertNull(serviceClientStore.find(owned.pod.podId(), service.clientId), "still absent to every read")
    assertEquals("invalid_client", services.tokenError(services.token(owned.pod, service)))
  }

  // ── Binding and races ───────────────────────────────────────────────────────

  @Test
  fun `a submission is redeemed once`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))

    assertEquals(200, flow.submit(page, owned.cookie, scopes = setOf("$c#read")).statusCode)
    val replay = flow.submit(page, owned.cookie, scopes = setOf("$c#write"))

    assertEquals(403, replay.statusCode, replay.responseBody)
    assertEquals(setOf("$c#read"), stored(owned, service).scopes)
  }

  @Test
  fun `a screen for one service submitted for another is refused, and writes nothing`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val first = services.register(owned.pod)
    val second = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, first.clientId, owned.cookie))

    val swapped = flow.submit(page, owned.cookie, scopes = setOf("$c#read"), extra = listOf("client_id" to second.clientId))

    assertEquals(400, swapped.statusCode, swapped.responseBody)
    assertTrue(swapped.getHeader("Location").isNullOrBlank())
    assertEquals(emptySet(), stored(owned, first).scopes)
    assertEquals(emptySet(), stored(owned, second).scopes)
  }

  @Test
  fun `a screen rendered before the grants changed is refused, and writes nothing`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val d = owned.context("d")
    val service = services.register(owned.pod)
    val stale = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    confirm(owned, service, setOf("$c#read"))

    val late = flow.submit(stale, owned.cookie, scopes = setOf("$d#write"))

    assertEquals(409, late.statusCode, late.responseBody)
    assertEquals(setOf("$c#read"), stored(owned, service).scopes)
  }

  @Test
  fun `a context created in the dialog stays private when the replace is refused, and the page says so`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val stale = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    confirm(owned, service, setOf("$c#read"))

    val late = flow.submit(stale, owned.cookie, scopes = emptySet(), newContexts = mapOf("syncer" to setOf("read", "write")))

    assertEquals(409, late.statusCode, late.responseBody)
    val syncer = owned.contextIri("syncer")
    assertTrue(syncer in late.responseBody, "the page names the context it created: ${late.responseBody}")
    assertTrue(podFacade.getContexts(owned.pod.name).any { it.toString() == syncer }, "the context stays")
    assertFalse(podFacade.getPublicContexts(podName = owned.pod.name).any { it.toString() == syncer }, "and is private")
    assertEquals(setOf("$c#read"), stored(owned, service).scopes, "and the service holds nothing on it")
  }

  @Test
  fun `a context created in the dialog is granted where nothing raced it`() {
    val owned = ownedPod()
    val service = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))

    val confirmed = flow.submit(page, owned.cookie, scopes = emptySet(), newContexts = mapOf("syncer" to setOf("manage")))

    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    assertEquals(setOf("${owned.contextIri("syncer")}#manage"), stored(owned, service).scopes)
  }

  @Test
  fun `a context deleted while the dialog is open is granted to nobody`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val gone = owned.context("gone")
    val service = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    podFacade.removeContext(owned.pod.name, URI(gone))

    val confirmed = flow.submit(page, owned.cookie, scopes = setOf("$c#read", "$gone#read"))

    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    assertEquals(setOf("$c#read"), stored(owned, service).scopes)
  }

  @Test
  fun `a registration removed while the dialog is open is granted nothing`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val page = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    val registration = stored(owned, service)
    assertTrue(serviceClientStore.remove(owned.pod.podId(), service.clientId, registration.id))

    val confirmed = flow.submit(page, owned.cookie, scopes = setOf("$c#read"))

    assertEquals(404, confirmed.statusCode, confirmed.responseBody)
    assertNull(serviceClientStore.find(owned.pod.podId(), service.clientId))
  }

  @Test
  fun `a delegated screen and a service screen are each refused at the other's route`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val service = services.register(owned.pod)
    val app = flow.register(owned.pod)
    val servicePage = services.page(services.open(owned.pod, service.clientId, owned.cookie))
    val delegatedPage = DelegatedAccessFlow.ConsentPage.of(flow.authorize(owned.pod, app, owned.cookie))

    val atDelegated = postForm("${podBase(owned.pod)}/_system/auth/authorize/consent", owned.cookie, servicePage.hidden, "$c#read")
    val atService = postForm(services.consentUrl(owned.pod), owned.cookie, delegatedPage.hidden, "$c#read")

    assertEquals(400, atDelegated.statusCode, atDelegated.responseBody)
    assertEquals(400, atService.statusCode, atService.responseBody)
    assertEquals(emptySet(), stored(owned, service).scopes)
  }

  // ── What the dialog claims ──────────────────────────────────────────────────

  @Test
  fun `two services with one name are each granted only their own access`() {
    val owned = ownedPod()
    val c = owned.context("c")
    val first = services.register(owned.pod, name = "sempods-syncer")
    val second = services.register(owned.pod, name = "sempods-syncer")

    val page = services.open(owned.pod, first.clientId, owned.cookie)
    assertTrue(first.clientId in page.responseBody && second.clientId !in page.responseBody, "the page names the one it grants")
    flow.submit(services.page(page), owned.cookie, scopes = setOf("$c#read"))

    assertEquals(setOf("$c#read"), stored(owned, first).scopes)
    assertEquals(emptySet(), stored(owned, second).scopes)
  }

  @Test
  fun `the dialog names the service as a claim, shows no return address, and says nobody asked`() {
    val owned = ownedPod()
    val service = services.register(owned.pod, name = "sempods-syncer", redirectUris = listOf("https://evil.example/cb"))

    val page = services.open(owned.pod, service.clientId, owned.cookie, redirectUri = "https://evil.example/cb")

    assertEquals(200, page.statusCode, page.responseBody)
    val text = page.responseBody
    assertTrue("calls itself" in text && "sempods-syncer" in text, "the name is shown as the service's claim")
    assertTrue(service.clientId in text, "beside the identifier this pod assigned")
    assertFalse("evil.example" in text, "the return address is not shown as the service's origin")
    for (claim in listOf("wants access", "asks for", "requested", "is asking")) {
      assertFalse(claim in text, "the page does not claim the service asked: '$claim'")
    }
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    val cookie: String get() = signIn(pod.name, webId).cookie

    fun contextIri(path: String): String = sempodsUriBuilder.buildContext(pod.name, path).toString()

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

  private fun confirm(owned: Owned, service: Service, scopes: Set<String>) =
    services.confirm(owned.pod, service.clientId, owned.cookie, scopes)

  private fun stored(owned: Owned, service: Service) =
    assertNotNull(serviceClientStore.find(owned.pod.podId(), service.clientId), "registration ${service.clientId}")

  private fun createContext(owned: Owned, path: String, bearer: String): TestHttpResponse =
    http.preparePut("${podBase(owned.pod)}/_system/contexts/$path")
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("{}")
      .execute()

  private fun writeNote(context: String, owned: Owned, bearer: String): TestHttpResponse {
    val note = sempodsTestFactory.eventUri(owned.pod.name)
    return http.preparePut("$note?context=${enc(context)}")
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("<$note> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <$context> .")
      .execute()
  }

  private fun read(resource: URI, bearer: String): TestHttpResponse = http.prepareGet(resource.toString())
    .addHeader("Accept", "application/ld+json")
    .addHeader("Authorization", "Bearer $bearer")
    .execute()

  /** Posts a consent form's hidden fields with one row ticked, as a hand-built submission would. */
  private fun postForm(url: String, cookie: String, hidden: Map<String, String>, scope: String): TestHttpResponse =
    http.preparePost(url)
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", cookie)
      .setBody((hidden.toList() + ("scope" to scope)).joinToString("&") { (name, value) -> "$name=${enc(value)}" })
      .setFollowRedirect(false).execute()

  private fun podBase(pod: PodDbo) = "${SempodsModule.config.apiBaseUrl}${pod.name}"
}
