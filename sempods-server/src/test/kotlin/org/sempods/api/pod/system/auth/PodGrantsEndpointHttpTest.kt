package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.client.SempodsPodGrants
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsStatusException
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.pods.grants.GRANTS_MANAGE_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.mongo.persist.toHostedPod
import org.sempods.pods.mongo.persist.toPodId
import org.sempods.pods.oauth.PodRefreshTokenStore
import java.net.URI
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's grant management under `{pod}/_system/auth/grants`, through the management authority
 * a service client holds for the owner: who reaches each context, grouped by person, and the
 * withdrawal of one direct grant, which holds on the holder's next request.
 */
class PodGrantsEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var services: ServiceAccessFlow

  @Inject
  private lateinit var delegated: DelegatedAccessFlow

  @Inject
  private lateinit var didWeb: DidWebAccessFlow

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  private lateinit var refreshTokenStore: PodRefreshTokenStore

  // ── Authorization ───────────────────────────────────────────────────────────

  @Test
  fun `the management authority carries grants manage and the pod advertises it`() {
    val owned = ownedPod()
    val service = managing(owned)

    val minted = didWeb.json(didWeb.token(owned.pod, service.assertion(owned.pod)))
    assertTrue(GRANTS_MANAGE_SCOPE in (minted["scope"] as String).split(" "), "token scope: ${minted["scope"]}")
    val metadata = http.prepareGet("${podBase(owned)}/.well-known/oauth-authorization-server").execute()
    assertEquals(listOf("contexts:manage", GRANTS_MANAGE_SCOPE), didWeb.json(metadata)["sempods_service_management_scopes"])
  }

  @Test
  fun `every route answers 401 without a token`() {
    val owned = ownedPod()
    val notes = owned.context("notes")

    for (response in everyRoute(owned, notes, bearer = null)) {
      assertEquals(401, response.statusCode, response.responseBody)
      assertNotNull(response.getHeader("WWW-Authenticate"))
    }
  }

  @Test
  fun `every route answers 403 to a bearer without the authority`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#write"), grantedBy = owned.webId)

    for (bearer in listOf(
      mintOwnerPodToken(owned.pod.name, owned.webId),
      mintScopedToken(owned.pod.name, listOf("$notes#read"), webId = bob),
      mintContextsManagerToken(owned.pod.name, owned.webId),
    )) {
      for (response in everyRoute(owned, notes, bearer)) {
        assertEquals(403, response.statusCode, response.responseBody)
      }
    }
  }

  @Test
  fun `after a change of owner the authority answers 403`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val token = didWeb.accessToken(owned.pod, managing(owned))

    podDao.setOwner(checkNotNull(owned.pod.id), webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))

    for (response in everyRoute(owned, notes, token)) {
      assertEquals(403, response.statusCode, response.responseBody)
    }
  }

  @Test
  fun `an authorization request may not ask for grants manage`() {
    val owned = ownedPod()
    val app = delegated.register(owned.pod)

    val response = delegated.authorize(owned.pod, app, owned.cookie, scope = GRANTS_MANAGE_SCOPE)

    assertEquals(303, response.statusCode, response.responseBody)
    assertEquals("invalid_scope", delegated.query(response)["error"])
  }

  // ── Listing ─────────────────────────────────────────────────────────────────

  @Test
  fun `the holders of a context are grouped into people with their apps, the owner's apps, and services`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#write"), grantedBy = owned.webId)
    mintScopedToken(owned.pod.name, listOf("$notes#read"), webId = bob)
    mintScopedToken(owned.pod.name, listOf("$notes#write"), webId = owned.webId)
    val sync = services.register(owned.pod, name = "Notes Sync")
    services.confirm(owned.pod, sync.clientId, owned.cookie, setOf("$notes#read"))
    services.accessToken(owned.pod, sync)

    val holders = holders(owned, notes, token)

    assertEquals(notes, holders["context_iri"])
    val person = list(holders["people"]).single()
    assertEquals(bob, person["web_id"])
    assertEquals(listOf("read", "write"), person["permissions"])
    assertEquals(listOf(mapOf("permission" to "write", "inherited_from" to null)), person["grants"])
    val delegation = list(person["delegations"]).single()
    assertEquals(MINTED_CLIENT_ID, delegation["client_id"])
    assertEquals(listOf("read"), delegation["permissions"])
    val ownerApp = list(holders["apps"]).single()
    assertEquals(MINTED_CLIENT_ID, ownerApp["client_id"])
    assertEquals(listOf("read", "write"), ownerApp["permissions"])
    val service = list(holders["services"]).single()
    assertEquals(sync.clientId, service["client_id"])
    assertEquals("Notes Sync", service["client_name"])
    assertNotNull(service["last_used_at"], "the token it just received")
    assertTrue((service["last_used_at"] as String).endsWith("Z"), "RFC 3339 in UTC: ${service["last_used_at"]}")
    assertTrue(service["grants_version"] is Number)
  }

  @Test
  fun `an app's last use is when it last received a refresh token for that person`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    mintScopedToken(owned.pod.name, listOf("$notes#write"), webId = owned.webId)
    assertNull(list(holders(owned, notes, token)["apps"]).single()["last_used_at"], "no refresh token yet")

    refreshTokenStore.issueNewFamily(
      pod = checkNotNull(owned.pod.id).toPodId(),
      podName = owned.pod.name,
      clientId = MINTED_CLIENT_ID,
      webId = owned.webId,
      scopes = emptySet(),
      lifetime = PodRefreshTokenStore.Lifetime.SESSION,
    )

    assertNotNull(list(holders(owned, notes, token)["apps"]).single()["last_used_at"])
  }

  @Test
  fun `a grant from an ancestor's manage names the ancestor`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val work = owned.context("notes/work")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#manage", "$work#read"), grantedBy = owned.webId)

    val person = list(holders(owned, work, token)["people"]).single()

    assertEquals(listOf("read", "write", "manage"), person["permissions"])
    assertEquals(
      listOf(mapOf("permission" to "read", "inherited_from" to null), mapOf("permission" to "manage", "inherited_from" to notes)),
      person["grants"],
    )
  }

  @Test
  fun `the counts answer every context in one request`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val work = owned.context("notes/work")
    val empty = owned.context("empty")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    podGrantsFacade.addWebIdGrants(owned.hosted(), person(), listOf("$notes#manage"), grantedBy = owned.webId)
    podGrantsFacade.addWebIdGrants(owned.hosted(), person(), listOf("$work#read"), grantedBy = owned.webId)
    mintScopedToken(owned.pod.name, listOf("$work#write"), webId = owned.webId)

    val response = http.prepareGet("${podBase(owned)}/_system/auth/grants").addHeader("Authorization", "Bearer $token").execute()

    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("no-store", response.getHeader("Cache-Control"))
    val counts = list(json(response)["contexts"]).associate { it["context_iri"] to listOf(it["people"], it["apps"], it["services"]) }
    assertEquals<Map<Any?, List<Any?>>>(mapOf(empty to listOf(0, 0, 0), notes to listOf(1, 0, 0), work to listOf(2, 1, 0)), counts)
  }

  @Test
  fun `an unregistered context is 404 and a segment that is not base64url is 400`() {
    val owned = ownedPod()
    val token = didWeb.accessToken(owned.pod, managing(owned))

    assertEquals(404, get("${podBase(owned)}/_system/auth/grants/${b64(owned.contextIri("missing"))}", token).statusCode)
    assertEquals(400, get("${podBase(owned)}/_system/auth/grants/not+base64", token).statusCode)
  }

  // ── Withdrawing ─────────────────────────────────────────────────────────────

  @Test
  fun `withdrawing a person cascades, and their app is refused on its next request`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val note = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#write"), grantedBy = owned.webId)
    val bobsApp = mintScopedToken(owned.pod.name, listOf("$notes#read"), webId = bob)
    assertEquals(200, read(note, bobsApp).statusCode, "reachable before")

    val withdrawn = delete("${grantsOf(owned, notes)}/people/${b64(bob)}", token)

    assertEquals(204, withdrawn.statusCode, withdrawn.responseBody)
    assertRefused(read(note, bobsApp))
    assertTrue(list(holders(owned, notes, token)["people"]).none { it["web_id"] == bob }, "no longer listed")
  }

  @Test
  fun `an inherited grant is refused with the ancestor it comes from`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val work = owned.context("notes/work")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#manage"), grantedBy = owned.webId)

    val refused = delete("${grantsOf(owned, work)}/people/${b64(bob)}", token)

    assertEquals(409, refused.statusCode, refused.responseBody)
    assertEquals(listOf(notes), json(refused)["inherited_from"])
    assertEquals(bob, list(holders(owned, work, token)["people"]).single()["web_id"], "nothing withdrawn")
  }

  @Test
  fun `withdrawing a direct grant leaves an inherited one standing`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val work = owned.context("notes/work")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#manage", "$work#read"), grantedBy = owned.webId)

    assertEquals(204, delete("${grantsOf(owned, work)}/people/${b64(bob)}", token).statusCode)

    val person = list(holders(owned, work, token)["people"]).single()
    assertEquals(listOf(mapOf("permission" to "manage", "inherited_from" to notes)), person["grants"])
  }

  @Test
  fun `withdrawing the owner's delegation refuses that app on its next request`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val note = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val ownersApp = mintScopedToken(owned.pod.name, listOf("$notes#write"), webId = owned.webId)
    assertEquals(200, read(note, ownersApp).statusCode, "reachable before")

    val withdrawn = delete("${grantsOf(owned, notes)}/apps/${b64(MINTED_CLIENT_ID)}", token)

    assertEquals(204, withdrawn.statusCode, withdrawn.responseBody)
    assertRefused(read(note, ownersApp))
    assertEquals(emptyList<Map<String, Any?>>(), list(holders(owned, notes, token)["apps"]))
    assertEquals(404, delete("${grantsOf(owned, notes)}/apps/${b64(MINTED_CLIENT_ID)}", token).statusCode, "nothing left to withdraw")
  }

  @Test
  fun `withdrawing a service needs the grants version it was read at`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val other = owned.context("other")
    val note = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val sync = services.register(owned.pod)
    services.confirm(owned.pod, sync.clientId, owned.cookie, setOf("$notes#read", "$other#read"))
    val syncToken = services.accessToken(owned.pod, sync)
    assertEquals(200, read(note, syncToken).statusCode, "reachable before")
    val version = (list(holders(owned, notes, token)["services"]).single()["grants_version"] as Number).toLong()
    val route = "${grantsOf(owned, notes)}/services/${b64(sync.clientId)}"

    assertEquals(428, delete(route, token).statusCode)
    assertEquals(400, delete(route, token, ifMatch = "*").statusCode)
    assertEquals(412, delete(route, token, ifMatch = "\"${version + 1}\"").statusCode)
    val withdrawn = delete(route, token, ifMatch = "\"$version\"")

    assertEquals(204, withdrawn.statusCode, withdrawn.responseBody)
    assertRefused(read(note, syncToken))
    assertEquals(listOf(other), services.contexts(owned.pod, syncToken), "its other grant stays")
    assertEquals(412, delete(route, token, ifMatch = "\"$version\"").statusCode, "the version moved")
  }

  @Test
  fun `a former owner's delegations are listed under them and withdrawn with them`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val formerApp = mintScopedToken(owned.pod.name, listOf("$notes#write"), webId = owned.webId)
    val note = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val newOwner = Owned(owned.pod, webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))
    podDao.setOwner(checkNotNull(owned.pod.id), newOwner.webId)
    val token = didWeb.accessToken(owned.pod, managing(newOwner))

    // `seedEvent` writes through an app of its own, which is listed beside the former owner.
    val former = list(holders(newOwner, notes, token)["people"]).single { it["web_id"] == owned.webId }
    assertEquals(emptyList<Any?>(), former["grants"], "holding no grant of their own")
    assertEquals(MINTED_CLIENT_ID, list(former["delegations"]).single()["client_id"])

    assertEquals(204, delete("${grantsOf(newOwner, notes)}/people/${b64(owned.webId)}", token).statusCode)
    assertRefused(read(note, formerApp))
  }

  @Test
  fun `a holder that reaches nothing on the context is 404`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val token = didWeb.accessToken(owned.pod, managing(owned))

    assertEquals(404, delete("${grantsOf(owned, notes)}/people/${b64(person())}", token).statusCode)
    assertEquals(404, delete("${grantsOf(owned, notes)}/apps/${b64("dyn:nobody")}", token).statusCode)
    assertEquals(404, delete("${grantsOf(owned, notes)}/services/${b64("svc:nobody")}", token, ifMatch = "\"1\"").statusCode)
  }

  @Test
  fun `the client reads what the pod answers and withdraws through it`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val work = owned.context("notes/work")
    val token = didWeb.accessToken(owned.pod, managing(owned))
    val bob = person()
    podGrantsFacade.addWebIdGrants(owned.hosted(), bob, listOf("$notes#manage", "$work#read"), grantedBy = owned.webId)
    mintScopedToken(owned.pod.name, listOf("$work#read"), webId = bob)
    val grants = podAccess.sessionFor(owned.pod.name, SempodsRequestAuth.bearer(token)).let { SempodsPodGrants(it.session, it.calls) }

    assertEquals(1, checkNotNull(grants.counts().body).single { it.contextIri == notes }.people, "Bob, and nobody else on notes")
    val person = checkNotNull(grants.holders(work).body).people.single { it.webId == bob }
    assertEquals(listOf("read", "write", "manage"), person.permissions)
    assertEquals(listOf(notes), person.grants.mapNotNull { it.inheritedFrom })
    assertEquals(MINTED_CLIENT_ID, person.delegations.single().clientId)

    assertTrue(grants.withdrawPerson(work, bob))
    assertEquals(409, assertThrows<SempodsStatusException> { grants.withdrawPerson(work, bob) }.status, "only the inherited one is left")
    assertTrue(grants.withdrawPerson(notes, bob))
    assertTrue(checkNotNull(grants.holders(work).body).people.none { it.webId == bob })
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  /** One request to every route, for the authorization cases. */
  private fun everyRoute(owned: Owned, context: String, bearer: String?): List<TestHttpResponse> {
    fun auth(request: org.sempods.commons.okhttp.TestHttpRequest) = request.apply { bearer?.let { addHeader("Authorization", "Bearer $it") } }
    val base = grantsOf(owned, context)
    return listOf(
      auth(http.prepareGet("${podBase(owned)}/_system/auth/grants")).execute(),
      auth(http.prepareGet(base)).execute(),
      auth(http.prepareDelete("$base/people/${b64(person())}")).execute(),
      auth(http.prepareDelete("$base/apps/${b64("dyn:x")}")).execute(),
      auth(http.prepareDelete("$base/services/${b64("svc:x")}").addHeader("If-Match", "\"1\"")).execute(),
    )
  }

  /** A did:web service registered on [owned]'s pod, holding the authority its owner approved. */
  private fun managing(owned: Owned): DidWebService {
    val service = didWeb.registered(owned.pod, DidWebServiceFixture.newService())
    val page = http.prepareGet(services.consentUrl(owned.pod))
      .addQueryParam("client_id", service.did)
      .addQueryParam("state", "manage-1")
      .addQueryParam("authority", "manage")
      .addHeader("Cookie", owned.cookie)
      .setFollowRedirect(false)
      .execute()
    assertTrue("take that access away" in page.responseBody, "the page names the grants it may withdraw")
    val confirmed = delegated.submit(services.page(page), owned.cookie, scopes = emptySet())
    assertEquals(200, confirmed.statusCode, confirmed.responseBody)
    return service
  }

  private fun holders(owned: Owned, context: String, bearer: String): Map<String, Any?> {
    val response = get(grantsOf(owned, context), bearer)
    assertEquals(200, response.statusCode, response.responseBody)
    return json(response)
  }

  private fun get(url: String, bearer: String): TestHttpResponse =
    http.prepareGet(url).addHeader("Authorization", "Bearer $bearer").execute()

  private fun delete(url: String, bearer: String, ifMatch: String? = null): TestHttpResponse =
    http.prepareDelete(url).addHeader("Authorization", "Bearer $bearer").apply { ifMatch?.let { addHeader("If-Match", it) } }.execute()

  private fun read(resource: URI, bearer: String): TestHttpResponse =
    http.prepareGet(resource.toString()).addHeader("Authorization", "Bearer $bearer").addHeader("Accept", "application/n-quads").execute()

  private fun assertRefused(response: TestHttpResponse) =
    assertTrue(response.statusCode == 403 || response.statusCode == 404, "status=${response.statusCode} body=${response.responseBody}")

  private fun grantsOf(owned: Owned, context: String) = "${podBase(owned)}/_system/auth/grants/${b64(context)}"

  private fun b64(value: String) = UriEncodingUtil.encodeUriToUrlSafeBase64(URI(value))

  private fun person() = "https://id.test/person-${UUID.randomUUID()}"

  @Suppress("UNCHECKED_CAST")
  private fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  @Suppress("UNCHECKED_CAST")
  private fun list(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>

  private fun PodDbo.hosted() = toHostedPod(sempodsUriBuilder)

  private fun Owned.hosted() = pod.hosted()

  private fun podBase(owned: Owned) = "${SempodsModule.config.apiBaseUrl}${owned.pod.name}"

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
    return Owned(sempodsTestFactory.newPod(ownerUser = owner, createPublicContext = false), webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private companion object {
    /** The app `mintScopedToken` delegates to. */
    const val MINTED_CLIENT_ID = "did:web:test.example"
  }
}
