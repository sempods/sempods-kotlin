package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsTestSetup
import org.sempods.api.pod.system.auth.ServiceAccessFlow.Service
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.assertPodBearerChallenge
import org.sempods.client.SempodsPodServiceClients
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An owner's service clients over the API: registering one with the owner's authority, the reads,
 * the grant replace, rotation and revocation (`sempods-server/docs/auth/service-clients.md` §"Managing service
 * clients"). The consent that gives a service contexts in the browser is `ServiceConsentHttpTest`'s.
 *
 * A call that succeeds goes through the published client. Every refusal runs at the wire, because
 * the management authority is told apart by the scopes its bearer carries and nothing else; so do
 * the cache rules and the tags the API answers with.
 */
@Suppress("UNCHECKED_CAST")
class PodServiceClientsEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  @Inject
  private lateinit var serviceClientStore: PodServiceClientStore

  @Inject
  private lateinit var podDao: PodDao

  @Inject
  private lateinit var services: ServiceAccessFlow

  private val ownerToolClientId = "did:web:localhost%3A5173"
  private val redirectUri = "http://localhost:5173/callback"
  private val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  private val serviceBody = """{"client_name":"Notes Sync","grant_types":["client_credentials"],""" +
    """"token_endpoint_auth_method":"client_secret_basic"}"""

  // ── The whole life of one service ───────────────────────────────────────────

  @Test
  fun `a registered service is granted, used, emptied, regranted, rotated and revoked`() = withSetup {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val service = services.register(owned.pod)

    // The owner's consent names the service by its label, beside the pod's own two facts.
    val page = services.open(owned.pod, service.clientId, signIn(owned.pod.name, owned.webId).cookie)
    assertEquals(200, page.statusCode, page.responseBody)
    assertTrue("Notes Sync" in page.responseBody, "the dialog names the service by its label")
    assertTrue(service.clientId in page.responseBody, "and shows the identifier this pod assigned")
    assertTrue(
      Instant.ofEpochSecond(service.issuedAt).toString() in page.responseBody,
      "and when it was registered",
    )
    confirm(owned, service, setOf("$notes#read"))

    // Inside the grant and not outside it.
    val serviceToken = services.accessToken(owned.pod, service.clientId, service.secret)
    assertEquals(listOf(notes), services.contexts(owned.pod, serviceToken))
    assertFalse(diary in services.contexts(owned.pod, serviceToken))

    // The owner's list: the grant, and when it was last used.
    val owners = ownersApi(owned, approveManagement(owned))
    val listed = checkNotNull(owners.list().body).single { it.clientId == service.clientId }
    assertEquals(setOf("$notes#read"), listed.scopes)
    assertEquals("Notes Sync", listed.clientName)
    assertEquals("registered", listed.origin)
    assertNotNull(listed.lastUsedAt, "the token just minted is what makes it visible")

    // Replace the grants with none: the registration stays, holds nothing and mints nothing.
    val emptied = owners.replaceGrants(service.clientId, emptyList(), checkNotNull(owners.get(service.clientId).body).grantsVersion)
    assertEquals(200, emptied.status)
    assertEquals(emptySet(), checkNotNull(emptied.body).scopes)
    assertEquals(400, services.token(owned.pod, service.clientId, service.secret).statusCode)
    assertEquals(
      emptyList(),
      services.contexts(owned.pod, serviceToken),
      "a token minted before the removal reaches nothing on its next request",
    )
    assertTrue(checkNotNull(owners.list().body).any { it.clientId == service.clientId })

    // And a later consent grants it again.
    confirm(owned, service, setOf("$diary#write"))

    // Rotation: the old secret stops at once, the new one works, the grants stay.
    val rotated = owners.rotateSecret(service.clientId)
    assertEquals(200, rotated.status)
    val newSecret = checkNotNull(rotated.body).clientSecret
    assertEquals(401, services.token(owned.pod, service.clientId, service.secret).statusCode, "the old secret is gone")
    val afterRotation = services.accessToken(owned.pod, service.clientId, newSecret)
    assertEquals(listOf(diary), services.contexts(owned.pod, afterRotation))
    val (written, entry) = writeNote(diary, owned, afterRotation)
    assertEquals(201, written.statusCode, written.responseBody)

    // Revocation: nothing mints, an outstanding token reaches nothing, the contexts and their data stay.
    assertTrue(owners.revoke(service.clientId))
    assertEquals(401, services.token(owned.pod, service.clientId, newSecret).statusCode)
    assertEquals(emptyList(), services.contexts(owned.pod, afterRotation))
    assertTrue(
      podFacade.getContexts(owned.pod.name).any { it.toString() == diary },
      "revoking a service leaves the owner's contexts where they are",
    )
    assertNotNull(
      podFacade.getResourceInContext(owned.pod.name, entry, URI(diary)),
      "and what the service wrote into them",
    )
    assertFalse(checkNotNull(owners.list().body).any { it.clientId == service.clientId })
  }

  // ── The owner's API ─────────────────────────────────────────────────────────

  @Test
  fun `a management bearer registers a service active, and a replace of C#read lets it read C`() = withSetup {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val manager = approveManagement(owned)
    val owners = ownersApi(owned, manager)

    val registered = owners.register("Notes Sync")
    assertEquals(201, registered.status)
    val service = checkNotNull(registered.body)
    assertNull(service.activationExpiresAt, "the owner's own authority activates it")
    val clientId = service.clientId
    assertTrue(clientId.startsWith("svc:"), clientId)

    val before = checkNotNull(owners.get(clientId).body)
    assertEquals(0L, before.grantsVersion)
    assertEquals(emptySet(), before.scopes)
    assertNull(before.activationExpiresAt)

    val replaced = owners.replaceGrants(clientId, listOf("$notes#read"), before.grantsVersion)
    assertEquals(200, replaced.status)
    assertEquals(1L, checkNotNull(replaced.body).grantsVersion)
    assertEquals(setOf("$notes#read"), checkNotNull(replaced.body).scopes)

    val serviceToken = services.accessToken(owned.pod, clientId, service.clientSecret)
    assertEquals(listOf(notes), services.contexts(owned.pod, serviceToken))
    assertFalse(diary in services.contexts(owned.pod, serviceToken))
    assertEquals(emptyList(), services.contexts(owned.pod, manager), "the management bearer itself reaches no data")
  }

  @Test
  fun `the owner's API answers uncached, tags the grants version, and lists no secret`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val manager = approveManagement(owned)

    val registered = register(owned, manager)
    assertEquals(201, registered.statusCode, registered.responseBody)
    assertFalse("activation_expires_at" in json(registered), "the owner's own authority activates it: ${registered.responseBody}")
    val clientId = json(registered)["client_id"] as String

    val before = read(owned, manager, clientId)
    assertEquals("\"0\"", before.tag)
    assertEquals(0, before.body["grants_version"])
    assertFalse("activation_expires_at" in before.body)

    val replaced = replaceGrants(owned, manager, clientId, """["$notes#read"]""", ifMatch = before.tag)
    assertEquals(200, replaced.statusCode, replaced.responseBody)
    assertEquals("\"1\"", replaced.getHeader("ETag"))
    assertEquals("no-store", replaced.getHeader("Cache-Control"))

    val listed = listServiceClients(owned, manager).single { it["client_id"] == clientId }
    assertFalse(listed.keys.any { "secret" in it }, "a list never carries a secret: $listed")

    val rotated = http.preparePost("${serviceClientsUrl(owned)}/${enc(clientId)}/secret")
      .addHeader("Authorization", "Bearer $manager")
      .execute()
    assertEquals(200, rotated.statusCode, rotated.responseBody)
    assertEquals("no-store", rotated.getHeader("Cache-Control"))
  }

  @Test
  fun `a replace names the version it read, and a stale one is 412 and changes nothing`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val manager = approveManagement(owned)
    val clientId = registeredClientId(owned, manager)
    val first = read(owned, manager, clientId).tag
    assertEquals(200, replaceGrants(owned, manager, clientId, """["$notes#read"]""", ifMatch = first).statusCode)

    val stale = replaceGrants(owned, manager, clientId, "[]", ifMatch = first)
    assertEquals(412, stale.statusCode, stale.responseBody)
    val missing = replaceGrants(owned, manager, clientId, "[]", ifMatch = null)
    assertEquals(428, missing.statusCode, missing.responseBody)
    for (malformed in listOf("*", "W/\"1\"", "\"1\", \"2\"", "1")) {
      val refused = replaceGrants(owned, manager, clientId, "[]", ifMatch = malformed)
      assertEquals(400, refused.statusCode, "$malformed: ${refused.responseBody}")
    }
    for (body in listOf("", "{}", "\"$notes#read\"", "[1]", "[null]", "not json")) {
      val refused = replaceGrants(owned, manager, clientId, body, ifMatch = "\"1\"")
      assertEquals(400, refused.statusCode, "$body: ${refused.responseBody}")
    }

    val after = read(owned, manager, clientId)
    assertEquals("$notes#read", after.body["scope"])
    assertEquals("\"1\"", after.tag)
  }

  @Test
  fun `a replace refuses public-read, OIDC scopes, a manage root at or above the namespace, and unknown contexts`() {
    val owned = ownedPod()
    owned.context("notes")
    val manager = approveManagement(owned)
    val clientId = registeredClientId(owned, manager)
    val namespace = "${podBase(owned)}/_system/contexts"

    for (scope in listOf(PUBLIC_READ_SCOPE, "openid", "offline_access", "$namespace#manage", "${podBase(owned)}#manage", "$namespace/absent#read")) {
      val refused = replaceGrants(owned, manager, clientId, """["$namespace/notes#read", "$scope"]""", ifMatch = "\"0\"")
      assertEquals(400, refused.statusCode, "$scope: ${refused.responseBody}")
    }
    assertEquals("", read(owned, manager, clientId).body["scope"])
  }

  @Test
  fun `a replace of a provisional registration activates it`() {
    val owned = ownedPod()
    val provisional = services.register(owned.pod)
    val manager = approveManagement(owned)
    assertTrue("activation_expires_at" in read(owned, manager, provisional.clientId).body)

    val activated = replaceGrants(owned, manager, provisional.clientId, "[]", ifMatch = "\"0\"")

    assertEquals(200, activated.statusCode, activated.responseBody)
    assertFalse("activation_expires_at" in json(activated), activated.responseBody)
  }

  // ── Who holds the authority ─────────────────────────────────────────────────

  @Test
  fun `a data token without the scope is 403 at registration and at the replace`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val existing = services.register(owned.pod)
    val dataToken = mintScopedToken(owned.pod.name, scopes = listOf("$notes#manage"), webId = "https://id.test/someone-else")
    val ownersApp = mintScopedToken(owned.pod.name, scopes = listOf("$notes#manage"), webId = owned.webId)

    for (bearer in listOf(dataToken, ownersApp)) {
      assertInsufficientScope(owned, register(owned, bearer))
      assertInsufficientScope(owned, replaceGrants(owned, bearer, existing.clientId, """["$notes#read"]""", ifMatch = "\"0\""))
    }
    assertSecretStands(owned, existing)
  }

  @Test
  fun `an authority approved before the consent promised registering can neither register nor assign`() = withSetup {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val existing = services.register(owned.pod)
    val earlier = mintServiceClientsManagerToken(owned.pod.name, owned.webId, consent = PrivilegedAuthorityRows.FIRST_CONSENT)
    val registrations = serviceClientStore.list(owned.pod.hosted.id).size

    assertInsufficientScope(owned, register(owned, earlier))
    assertInsufficientScope(owned, replaceGrants(owned, earlier, existing.clientId, """["$notes#read"]""", ifMatch = "\"0\""))
    assertEquals(registrations, serviceClientStore.list(owned.pod.hosted.id).size, "nothing was registered")
    val earlierApi = ownersApi(owned, earlier)
    assertEquals(200, earlierApi.list().status, "it still reads, as its consent said")
    assertSecretStands(owned, existing)

    // And it still takes a service away, as its consent said.
    assertEquals(200, earlierApi.rotateSecret(existing.clientId).status)
    assertTrue(earlierApi.revoke(existing.clientId))
  }

  @Test
  fun `a replace for an unknown service is 404, and a body of another media type is read after the bearer`() {
    val owned = ownedPod()
    owned.context("notes")
    val manager = approveManagement(owned)

    val unknown = replaceGrants(owned, manager, "svc:nobody", "[]", ifMatch = "\"0\"")
    assertEquals(404, unknown.statusCode, unknown.responseBody)

    val clientId = registeredClientId(owned, manager)
    fun plainText(bearer: String?) = http.preparePut(grantsUrl(owned, clientId))
      .addHeader("Content-Type", "text/plain")
      .addHeader("If-Match", "\"0\"")
      .apply { if (bearer != null) addHeader("Authorization", "Bearer $bearer") }
      .setBody("[]")
      .execute()
    assertPodBearerChallenge(plainText(null), owned.pod.name)
    assertEquals(200, plainText(manager).statusCode, "the body is what is read, whatever it is labelled")
  }

  @Test
  fun `the management consent says it registers services and gives them access`() {
    val page = authorizePage(ownedPod(), SERVICE_CLIENTS_MANAGE_SCOPE)
    assertEquals(200, page.statusCode, page.responseBody)
    assertTrue("register new services and give them access to your data" in page.responseBody, page.responseBody)
    assertTrue("change or take away the" in page.responseBody, page.responseBody)
    assertFalse("cannot give" in page.responseBody, "the old promise is gone")
  }

  @Test
  fun `a management authorization issues no refresh token`() {
    val owned = ownedPod()
    val tokens = approveManagementTokens(owned)
    assertNull(tokens["refresh_token"], "a management authority is an hour and no more")
    assertEquals(SERVICE_CLIENTS_MANAGE_SCOPE, tokens["scope"])
  }

  @Test
  fun `the two privileged scopes are not granted together`() {
    val owned = ownedPod()
    val response = authorizePage(owned, "$CONTEXTS_MANAGE_SCOPE $SERVICE_CLIENTS_MANAGE_SCOPE")
    assertEquals(303, response.statusCode, response.responseBody)
    assertEquals("invalid_scope", query(response)["error"])
  }

  @Test
  fun `a management bearer sees only its own pod`() = withSetup {
    val mine = ownedPod()
    val theirs = ownedPod()
    val theirService = services.register(theirs.pod)
    val manager = approveManagement(mine)

    val listed = checkNotNull(ownersApi(mine, manager).list().body)
    assertFalse(listed.any { it.clientId == theirService.clientId })

    val crossPod = http.prepareGet(serviceClientsUrl(theirs)).addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(401, crossPod.statusCode, crossPod.responseBody)
  }

  @Test
  fun `an operator-provisioned client's grants are the owner's, and its secret and registration are not`() = withSetup {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val appRoot = "${podBase(owned)}/_system/contexts/apps/backend"
    serviceClientStore.register(owned.pod.hosted, "backend", setOf("$appRoot#manage"), label = "backend")
    val manager = approveManagement(owned)
    val owners = ownersApi(owned, manager)

    val listed = checkNotNull(owners.list().body).single { it.clientId == "backend" }
    assertEquals("provisioned", listed.origin)
    val narrowed = owners.replaceGrants("backend", listOf("$notes#read"), checkNotNull(owners.get("backend").body).grantsVersion)
    assertEquals(200, narrowed.status)
    assertEquals(setOf("$notes#read"), checkNotNull(narrowed.body).scopes)

    val rotated = http.preparePost("${serviceClientsUrl(owned)}/backend/secret")
      .addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(403, rotated.statusCode, rotated.responseBody)
    val revoked = http.prepareDelete("${serviceClientsUrl(owned)}/backend")
      .addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(403, revoked.statusCode, revoked.responseBody)
  }

  @Test
  fun `a management bearer from an owner signed in under a linked alias works`() = withSetup {
    val owned = ownedPod()
    val alias = "https://id.test/oidc/${org.sempods.commons.tests.TestUtil.randomId()}"
    val manager = approveManagement(owned, signedInAs = alias, alsoKnownAs = listOf(owned.webId))

    assertEquals(200, ownersApi(owned, manager).list().status)
  }

  @Test
  fun `a management authority approved on its own is disconnected in the ordinary dialog`() {
    // It writes no grant, so a dialog that asked only about grants offered no way to end it early.
    val owned = ownedPod()
    val manager = approveManagement(owned)

    val page = authorizePage(owned, PUBLIC_READ_SCOPE)
    assertTrue("disconnectBtn" in page.responseBody, "the authority is something this app holds")
    val disconnected = disconnect(owned, page)
    assertEquals("app disconnected", query(disconnected)["error_description"], disconnected.getHeader("Location"))

    val listed = http.prepareGet(serviceClientsUrl(owned)).addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(401, listed.statusCode, listed.responseBody)
    assertFalse("disconnectBtn" in authorizePage(owned, PUBLIC_READ_SCOPE).responseBody, "nothing is left to end")
  }

  @Test
  fun `a management authority approved under an alias is disconnected by whoever the person signs in as`() {
    val owned = ownedPod()
    val alias = "https://id.test/oidc/${org.sempods.commons.tests.TestUtil.randomId()}"
    val manager = approveManagement(owned, signedInAs = alias, alsoKnownAs = listOf(owned.webId))

    val page = authorizePage(owned, PUBLIC_READ_SCOPE, alsoKnownAs = listOf(alias))
    assertTrue("disconnectBtn" in page.responseBody, "the alias is among the URIs that name the person")
    disconnect(owned, page, alsoKnownAs = listOf(alias))

    val listed = http.prepareGet(serviceClientsUrl(owned)).addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(401, listed.statusCode, listed.responseBody)
  }

  // ── Refused callers ─────────────────────────────────────────────────────────

  @Test
  fun `a missing bearer is answered exactly like a rejected one on every route`() {
    // SPS-CORE-015: every route here requires authentication.
    val owned = ownedPod()
    val existing = services.register(owned.pod)

    for (route in managementRoutes(owned, existing.clientId)) {
      val missing = route(null)
      val rejected = route("not-a-token")

      assertPodBearerChallenge(missing, owned.pod.name)
      assertEquals(rejected.statusCode, missing.statusCode)
      assertEquals(rejected.getHeader("WWW-Authenticate"), missing.getHeader("WWW-Authenticate"))
      assertEquals(rejected.getHeader("Content-Type"), missing.getHeader("Content-Type"))
      assertEquals(rejected.responseBody, missing.responseBody)
    }
    assertSecretStands(owned, existing)
  }

  @Test
  fun `a valid bearer without the owner's authority is 403 insufficient_scope on every route`() {
    val owned = ownedPod()
    val existing = services.register(owned.pod)
    val someoneElsesApp = mintScopedToken(owned.pod.name, scopes = emptyList(), webId = "https://id.test/someone-else")
    val formerOwner = approveManagement(owned)
    podDao.setOwner(checkNotNull(owned.pod.id), webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))

    for (bearer in listOf(someoneElsesApp, formerOwner)) {
      for (route in managementRoutes(owned, existing.clientId)) {
        assertInsufficientScope(owned, route(bearer))
      }
    }
    assertSecretStands(owned, existing)
  }

  private fun assertInsufficientScope(owned: Owned, response: TestHttpResponse) {
    assertEquals(403, response.statusCode, response.responseBody)
    val challenge = checkNotNull(response.getHeader("WWW-Authenticate"))
    assertTrue("error=\"insufficient_scope\"" in challenge, challenge)
    assertTrue("resource_metadata=\"${podBase(owned)}/.well-known/oauth-protected-resource\"" in challenge, challenge)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    fun context(path: String): String {
      val uri = URI("${podBase(this)}/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}$path")
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    return Owned(pod, webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private fun podBase(owned: Owned) = "${SempodsModule.config.apiBaseUrl}${owned.pod.name}"

  /** The owner's API on [owned]'s pod under [bearer], through the published client. */
  private fun SempodsTestSetup.ownersApi(owned: Owned, bearer: String): SempodsPodServiceClients =
    podAs(owned.pod.name, bearer = bearer).let { SempodsPodServiceClients(it.session, it.calls) }
  private fun serviceClientsUrl(owned: Owned) = "${podBase(owned)}/_system/auth/service-clients"
  private fun grantsUrl(owned: Owned, clientId: String) = "${serviceClientsUrl(owned)}/${enc(clientId)}/grants"
  private fun registerUrl(owned: Owned) = "${podBase(owned)}/_system/auth/register"
  private fun tokenUrl(owned: Owned) = "${podBase(owned)}/_system/auth/token"

  private fun authorizePage(
    owned: Owned,
    scope: String,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): TestHttpResponse = http.prepareGet("${podBase(owned)}/_system/auth/authorize")
    .addQueryParam("response_type", "code")
    .addQueryParam("client_id", ownerToolClientId)
    .addQueryParam("redirect_uri", redirectUri)
    .addQueryParam("state", "privileged")
    .addQueryParam("scope", scope)
    .addQueryParam("code_challenge", codeChallenge)
    .addQueryParam("code_challenge_method", "S256")
    .addHeader("Cookie", signIn(owned.pod.name, signedInAs, alsoKnownAs).cookie)
    .setFollowRedirect(false).execute()

  /** Authorize, approve and redeem one privileged scope — the first consent, as a browser runs it. */
  private fun approvePrivileged(
    owned: Owned,
    scope: String,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): Map<String, Any?> {
    val page = authorizePage(owned, scope, signedInAs, alsoKnownAs)
    assertEquals(200, page.statusCode, page.responseBody)
    val submitted = http.preparePost("${podBase(owned)}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(owned.pod.name, signedInAs, alsoKnownAs).cookie)
      .setBody(
        "client_id=${enc(ownerToolClientId)}&redirect_uri=${enc(redirectUri)}" +
          "&state=privileged&csrf=${enc(formToken(page))}&scope=${enc(scope)}",
      )
      .setFollowRedirect(false).execute()
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val code = checkNotNull(query(submitted)["code"]) { "no code: ${submitted.getHeader("Location")}" }
    val exchanged = postForm(
      tokenUrl(owned),
      "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirectUri)}" +
        "&client_id=${enc(ownerToolClientId)}&code_verifier=${enc(DelegatedAccessFlow.CODE_VERIFIER)}",
    )
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    return json(exchanged)
  }

  /** Takes the disconnect on an ordinary [page], the way its "Remove access" button does. */
  private fun disconnect(
    owned: Owned,
    page: TestHttpResponse,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): TestHttpResponse {
    val submitted = http.preparePost("${podBase(owned)}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(owned.pod.name, signedInAs, alsoKnownAs).cookie)
      .setBody(
        "client_id=${enc(ownerToolClientId)}&redirect_uri=${enc(redirectUri)}" +
          "&state=privileged&csrf=${enc(formToken(page))}&action=disconnect",
      )
      .setFollowRedirect(false).execute()
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    return submitted
  }

  private fun approveManagementTokens(owned: Owned) = approvePrivileged(owned, SERVICE_CLIENTS_MANAGE_SCOPE)

  private fun approveManagement(
    owned: Owned,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): String = approvePrivileged(owned, SERVICE_CLIENTS_MANAGE_SCOPE, signedInAs, alsoKnownAs)["access_token"] as String

  /** The owner's service consent for [service], confirmed with [scopes] ticked. */
  private fun confirm(owned: Owned, service: Service, scopes: Set<String>) =
    services.confirm(owned.pod, service.clientId, signIn(owned.pod.name, owned.webId).cookie, scopes)

  private fun formToken(page: TestHttpResponse): String =
    Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)?.groupValues?.get(1)
      ?: error("no form token in the rendered page: ${page.statusCode} ${page.responseBody.take(300)}")

  /** The secret still authenticates, so nothing rotated or revoked it: `invalid_scope`, not `invalid_client`. */
  private fun assertSecretStands(owned: Owned, service: Service) {
    val minted = services.token(owned.pod, service.clientId, service.secret)
    assertTrue("invalid_scope" in minted.responseBody, minted.responseBody)
  }

  /** List, read, replace the grants of, rotate and revoke [clientId], each sent with a bearer or without one. */
  private fun managementRoutes(owned: Owned, clientId: String): List<(String?) -> TestHttpResponse> {
    val registration = "${serviceClientsUrl(owned)}/${enc(clientId)}"
    fun TestHttpRequest.send(bearer: String?) =
      apply { if (bearer != null) addHeader("Authorization", "Bearer $bearer") }.execute()
    return listOf(
      { bearer -> http.prepareGet(serviceClientsUrl(owned)).send(bearer) },
      { bearer -> http.prepareGet(registration).send(bearer) },
      { bearer -> replaceGrants(owned, bearer, clientId, "[]", ifMatch = "\"0\"") },
      { bearer -> http.preparePost("$registration/secret").send(bearer) },
      { bearer -> http.prepareDelete(registration).send(bearer) },
    )
  }

  /** A registration with the service's metadata, sent with [bearer]. */
  private fun register(owned: Owned, bearer: String): TestHttpResponse =
    http.preparePost(registerUrl(owned))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody(serviceBody)
      .execute()

  /** The `client_id` of a service [bearer] registers. */
  private fun registeredClientId(owned: Owned, bearer: String): String = json(register(owned, bearer))["client_id"] as String

  private class Read(val body: Map<String, Any?>, val tag: String)

  /** The single read of [clientId]: its document and its `ETag`. */
  private fun read(owned: Owned, bearer: String, clientId: String): Read {
    val response = http.prepareGet("${serviceClientsUrl(owned)}/${enc(clientId)}").addHeader("Authorization", "Bearer $bearer").execute()
    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("no-store", response.getHeader("Cache-Control"))
    return Read(json(response), checkNotNull(response.getHeader("ETag")) { "a read carries the grants version" })
  }

  /** `PUT …/grants` with [body], and [ifMatch] where it is not null. */
  private fun replaceGrants(owned: Owned, bearer: String?, clientId: String, body: String, ifMatch: String?): TestHttpResponse =
    http.preparePut(grantsUrl(owned, clientId))
      .addHeader("Content-Type", "application/json")
      .apply { if (bearer != null) addHeader("Authorization", "Bearer $bearer") }
      .apply { if (ifMatch != null) addHeader("If-Match", ifMatch) }
      .setBody(body)
      .execute()

  private fun listServiceClients(owned: Owned, bearer: String): List<Map<String, Any?>> {
    val response = http.prepareGet(serviceClientsUrl(owned)).addHeader("Authorization", "Bearer $bearer").execute()
    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("no-store", response.getHeader("Cache-Control"))
    return json(response)["serviceClients"] as List<Map<String, Any?>>
  }

  /** Writes a fresh note into [context] as [bearer], at the resource layer. Answers the response and the note. */
  private fun writeNote(context: String, owned: Owned, bearer: String): Pair<TestHttpResponse, URI> {
    val note = sempodsTestFactory.eventUri(owned.pod.name)
    val response = http.preparePut("$note?context=${enc(context)}")
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("<$note> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <$context> .")
      .execute()
    return response to note
  }

  private fun postForm(url: String, body: String) = http.preparePost(url)
    .addHeader("Content-Type", "application/x-www-form-urlencoded")
    .setBody(body)
    .execute()

  private fun json(response: TestHttpResponse): Map<String, Any?> =
    JsonMappers.default().readValue(response.responseBody, Map::class.java) as Map<String, Any?>

  /** The query of a redirect's `Location`, decoded. */
  private fun query(response: TestHttpResponse): Map<String, String> {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return UrlUtil.queryParams(URI(location).rawQuery)
  }
}
