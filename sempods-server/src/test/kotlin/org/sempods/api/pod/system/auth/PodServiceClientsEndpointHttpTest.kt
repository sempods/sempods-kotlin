package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.SempodsUriBuilder
import org.sempods.api.assertPodBearerChallenge
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpRequest
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_INSTALL_SCOPE
import org.sempods.pods.mongo.persist.PodDao
import org.sempods.pods.mongo.persist.PodDbo
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
 * An owner-installed service client after its registration: the grant consent that gives it
 * contexts, and the list, rotation, grant removal and revocation an owner manages it with
 * (`docs/auth/service-clients.md` §"Managing an installed service client").
 *
 * Every step runs at the wire, installation included, because the two authorities involved —
 * installing and managing — are told apart by the scopes their bearers carry and nothing else.
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

  private val installerClientId = "did:web:localhost%3A5173"
  private val redirectUri = "http://localhost:5173/callback"
  private val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  private val installationBody = """{"client_name":"Notes Sync","grant_types":["client_credentials"],""" +
    """"token_endpoint_auth_method":"client_secret_basic"}"""

  // ── The whole life of one installation ──────────────────────────────────────

  @Test
  fun `an installed service is granted, used, narrowed, regranted, rotated and revoked`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val installed = install(owned)

    // The second consent names the service by its label, beside the pod's own two facts.
    val page = openGrant(owned, installed.clientId, "$notes#read")
    assertEquals(200, page.statusCode, page.responseBody)
    assertTrue("Notes Sync" in page.responseBody, "the dialog names the service by its label")
    assertTrue(installed.clientId in page.responseBody, "and shows the identifier this pod assigned")
    assertTrue(
      Instant.ofEpochSecond(installed.issuedAt).toString() in page.responseBody,
      "and when it was registered",
    )

    val granted = submitGrant(owned, formToken(page), installed.clientId, listOf("$notes#read"))
    assertEquals(303, granted.statusCode, granted.responseBody)
    val back = query(granted)
    assertEquals("granted", back["result"], "the caller learns it was granted")
    assertEquals("$notes#read", back["scope"])
    assertEquals("grant-1", back["state"])

    // Inside the grant and not outside it.
    val serviceToken = serviceToken(owned, installed.clientId, installed.secret)
    assertEquals(listOf(notes), contextsReachableBy(owned.pod, serviceToken))
    assertFalse(diary in contextsReachableBy(owned.pod, serviceToken))

    // The owner's list: the grant, when it was last used, and never a secret.
    val manager = approveManagement(owned)
    val listed = listServiceClients(owned, manager).single { it["client_id"] == installed.clientId }
    assertEquals("$notes#read", listed["scope"])
    assertEquals("Notes Sync", listed["client_name"])
    assertEquals("installed", listed["origin"])
    assertTrue(listed["last_used_at"] != null, "the token just minted is what makes it visible")
    assertFalse(listed.keys.any { "secret" in it }, "a list never carries a secret: $listed")

    // Remove the last grant: the registration stays, holds nothing and mints nothing.
    val narrowed = http.prepareDelete(grantsUrl(owned, installed.clientId))
      .addQueryParam("scope", "$notes#read")
      .addHeader("Authorization", "Bearer $manager")
      .execute()
    assertEquals(200, narrowed.statusCode, narrowed.responseBody)
    assertEquals("", json(narrowed)["scope"])
    assertEquals(400, mint(owned, installed.clientId, installed.secret).statusCode)
    assertEquals(
      emptyList(),
      contextsReachableBy(owned.pod, serviceToken),
      "a token minted before the removal reaches nothing on its next request",
    )
    assertTrue(listServiceClients(owned, manager).any { it["client_id"] == installed.clientId })

    // And a later consent grants it again.
    val regranted = submitGrant(owned, formToken(openGrant(owned, installed.clientId, "$diary#write")), installed.clientId, listOf("$diary#write"))
    assertEquals("granted", query(regranted)["result"], regranted.responseBody)

    // Rotation: the old secret stops at once, the new one works, the grants stay.
    val rotated = http.preparePost("${serviceClientsUrl(owned)}/${enc(installed.clientId)}/secret")
      .addHeader("Authorization", "Bearer $manager")
      .execute()
    assertEquals(200, rotated.statusCode, rotated.responseBody)
    assertEquals("no-store", rotated.getHeader("Cache-Control"))
    val newSecret = json(rotated)["client_secret"] as String
    assertEquals(401, mint(owned, installed.clientId, installed.secret).statusCode, "the old secret is gone")
    val afterRotation = serviceToken(owned, installed.clientId, newSecret)
    assertEquals(listOf(diary), contextsReachableBy(owned.pod, afterRotation))
    val (written, entry) = writeNote(diary, owned, afterRotation)
    assertEquals(201, written.statusCode, written.responseBody)

    // Revocation: nothing mints, an outstanding token reaches nothing, the contexts and their data stay.
    val revoked = http.prepareDelete("${serviceClientsUrl(owned)}/${enc(installed.clientId)}")
      .addHeader("Authorization", "Bearer $manager")
      .execute()
    assertEquals(204, revoked.statusCode, revoked.responseBody)
    assertEquals(401, mint(owned, installed.clientId, newSecret).statusCode)
    assertEquals(emptyList(), contextsReachableBy(owned.pod, afterRotation))
    assertTrue(
      podFacade.getContexts(owned.pod.name).any { it.toString() == diary },
      "revoking a service leaves the owner's contexts where they are",
    )
    assertNotNull(
      podFacade.getResourceInContext(owned.pod.name, entry, URI(diary)),
      "and what the service wrote into them",
    )
    assertFalse(listServiceClients(owned, manager).any { it["client_id"] == installed.clientId })
  }

  // ── The grant consent as a transaction ──────────────────────────────────────

  @Test
  fun `a grant consent is redeemed once`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val token = formToken(openGrant(owned, installed.clientId, "$notes#read"))

    assertEquals("granted", query(submitGrant(owned, token, installed.clientId, listOf("$notes#read")))["result"])
    val replay = submitGrant(owned, token, installed.clientId, listOf("$notes#read"))
    assertEquals(403, replay.statusCode, replay.responseBody)
  }

  @Test
  fun `an answer for a different service is refused`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val first = install(owned)
    val second = install(owned)
    val token = formToken(openGrant(owned, first.clientId, "$notes#read"))

    val swapped = submitGrant(owned, token, second.clientId, listOf("$notes#read"))

    assertEquals("invalid_request", query(swapped)["error"], swapped.responseBody)
    assertEquals("", serviceClientStore.find(owned.pod.hosted.id, second.clientId)!!.scopes.joinToString())
    assertEquals("", serviceClientStore.find(owned.pod.hosted.id, first.clientId)!!.scopes.joinToString())
  }

  @Test
  fun `an answer that widens or swaps the grant set is refused`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val installed = install(owned)

    val widened = submitGrant(
      owned, formToken(openGrant(owned, installed.clientId, "$notes#read")), installed.clientId,
      listOf("$notes#read", "$notes#write"),
    )
    assertEquals("invalid_scope", query(widened)["error"], widened.responseBody)

    val swapped = submitGrant(
      owned, formToken(openGrant(owned, installed.clientId, "$notes#read")), installed.clientId, listOf("$diary#manage"),
    )
    assertEquals("invalid_scope", query(swapped)["error"], swapped.responseBody)
    assertTrue(serviceClientStore.find(owned.pod.hosted.id, installed.clientId)!!.scopes.isEmpty())
  }

  @Test
  fun `a narrower answer grants only what stayed ticked, and an empty one is a refusal`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)

    val partial = submitGrant(
      owned, formToken(openGrant(owned, installed.clientId, "$notes#read $notes#write")), installed.clientId,
      listOf("$notes#read"),
    )
    assertEquals("$notes#read", query(partial)["scope"], partial.responseBody)

    val none = submitGrant(owned, formToken(openGrant(owned, installed.clientId, "$notes#write")), installed.clientId, emptyList())
    assertEquals("access_denied", query(none)["error"], none.responseBody)

    val refused = submitGrant(
      owned, formToken(openGrant(owned, installed.clientId, "$notes#write")), installed.clientId, listOf("$notes#write"),
      action = "refuse",
    )
    assertEquals("access_denied", query(refused)["error"], refused.responseBody)
    assertEquals(setOf("$notes#read"), serviceClientStore.find(owned.pod.hosted.id, installed.clientId)!!.scopes)
  }

  @Test
  fun `a transaction belongs to the browser session that opened it`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val token = formToken(openGrant(owned, installed.clientId, "$notes#read"))

    // Same person, another sign-in.
    val otherSession = sessionCookieSignedInAt(owned.pod.name, owned.webId, Instant.now().minusSeconds(120))
    val answered = submitGrant(owned, token, installed.clientId, listOf("$notes#read"), cookie = otherSession)

    assertEquals(403, answered.statusCode, answered.responseBody)
    assertTrue(serviceClientStore.find(owned.pod.hosted.id, installed.clientId)!!.scopes.isEmpty())
  }

  @Test
  fun `an approval is measured again when it is redeemed`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val token = formToken(openGrant(owned, installed.clientId, "$notes#read"))

    // The context goes while the dialog stands open: the approval does not bring it back.
    podFacade.removeContext(owned.pod.name, URI(notes))
    val answered = submitGrant(owned, token, installed.clientId, listOf("$notes#read"))

    assertEquals("access_denied", query(answered)["error"], answered.responseBody)
    assertTrue(serviceClientStore.find(owned.pod.hosted.id, installed.clientId)!!.scopes.isEmpty())
  }

  @Test
  fun `a service revoked while its dialog is open is not granted`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val token = formToken(openGrant(owned, installed.clientId, "$notes#read"))

    val manager = approveManagement(owned)
    assertEquals(
      204,
      http.prepareDelete("${serviceClientsUrl(owned)}/${enc(installed.clientId)}")
        .addHeader("Authorization", "Bearer $manager").execute().statusCode,
    )
    val answered = submitGrant(owned, token, installed.clientId, listOf("$notes#read"))

    assertEquals("access_denied", query(answered)["error"], answered.responseBody)
    assertNull(serviceClientStore.find(owned.pod.hosted.id, installed.clientId))
  }

  @Test
  fun `someone other than the owner is answered like a refusal`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)

    val stranger = openGrant(owned, installed.clientId, "$notes#read", cookie = signIn(owned.pod.name, "https://id.test/stranger").cookie)

    assertEquals(303, stranger.statusCode, stranger.responseBody)
    assertEquals("access_denied", query(stranger)["error"])
    assertEquals("the grant was not given", query(stranger)["error_description"])
  }

  @Test
  fun `an unknown service reads exactly like a refusal`() {
    val owned = ownedPod()
    val notes = owned.context("notes")

    val unknown = openGrant(owned, "svc:nobody", "$notes#read")

    assertEquals("access_denied", query(unknown)["error"], unknown.responseBody)
    assertEquals("the grant was not given", query(unknown)["error_description"])
  }

  @Test
  fun `a redirect the requesting client does not own is answered to the browser, not delivered`() {
    val owned = ownedPod()
    val installed = install(owned)

    val foreign = http.prepareGet(grantUrl(owned))
      .addQueryParam("client_id", installerClientId)
      .addQueryParam("redirect_uri", "https://evil.example/cb")
      .addQueryParam("service_client", installed.clientId)
      .addQueryParam("scope", "${owned.context("notes")}#read")
      .addHeader("Cookie", signIn(owned.pod.name, owned.webId).cookie)
      .setFollowRedirect(false).execute()

    assertEquals(400, foreign.statusCode, foreign.responseBody)
    assertTrue(foreign.getHeader("Location").isNullOrBlank())
  }

  @Test
  fun `a grant consent opened without a session resumes after the sign-in`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)

    val resumed = http.prepareGet(grantUrl(owned))
      .addQueryParam("client_id", installerClientId)
      .addQueryParam("redirect_uri", redirectUri)
      .addQueryParam("state", "grant-1")
      .addQueryParam("service_client", installed.clientId)
      .addQueryParam("scope", "$notes#read")
      .executeSignedInAs(owned.webId)

    assertEquals(200, resumed.statusCode, resumed.responseBody)
    assertTrue(installed.clientId in resumed.responseBody, "the callback resumes the grant dialog, not /authorize")
  }

  @Test
  fun `a read grant on an existing context reads it and writes nothing`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val existing = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(notes))
    val installed = install(owned)
    submitGrant(owned, formToken(openGrant(owned, installed.clientId, "$notes#read")), installed.clientId, listOf("$notes#read"))
    val token = serviceToken(owned, installed.clientId, installed.secret)

    assertEquals(listOf(notes), contextsReachableBy(owned.pod, token))
    assertEquals(200, read(existing, token).statusCode, "what the context held before the service existed")
    val write = writeNote(notes, owned, token).first
    assertEquals(403, write.statusCode, write.responseBody)
  }

  @Test
  fun `a write grant writes, and nothing beside the grants is read or written`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val entry = sempodsTestFactory.seedEvent(pod = owned.pod.name, context = URI(diary))
    val installed = install(owned)
    submitGrant(owned, formToken(openGrant(owned, installed.clientId, "$notes#write")), installed.clientId, listOf("$notes#write"))
    val token = serviceToken(owned, installed.clientId, installed.secret)

    val (written, note) = writeNote(notes, owned, token)
    assertEquals(201, written.statusCode, written.responseBody)
    assertEquals(200, read(note, token).statusCode, "a write grant reads what it wrote")

    val outside = writeNote(diary, owned, token).first
    assertEquals(403, outside.statusCode, outside.responseBody)
    assertEquals(404, read(entry, token).statusCode, "a context it was not granted reads as absent")
  }

  @Test
  fun `a service holding no grants is rotated, and granted later`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val manager = approveManagement(owned)

    val rotated = http.preparePost("${serviceClientsUrl(owned)}/${enc(installed.clientId)}/secret")
      .addHeader("Authorization", "Bearer $manager")
      .execute()
    assertEquals(200, rotated.statusCode, rotated.responseBody)
    val newSecret = json(rotated)["client_secret"] as String
    assertEquals(401, mint(owned, installed.clientId, installed.secret).statusCode, "the old secret is gone")
    val holdingNothing = mint(owned, installed.clientId, newSecret)
    assertEquals(400, holdingNothing.statusCode, holdingNothing.responseBody)
    assertTrue("invalid_scope" in holdingNothing.responseBody, "the new secret authenticates: ${holdingNothing.responseBody}")

    submitGrant(owned, formToken(openGrant(owned, installed.clientId, "$notes#read")), installed.clientId, listOf("$notes#read"))
    assertEquals(listOf(notes), contextsReachableBy(owned.pod, serviceToken(owned, installed.clientId, newSecret)))
  }

  @Test
  fun `a broader consent opened later does not widen one already open`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val diary = owned.context("diary")
    val installed = install(owned)
    val narrow = formToken(openGrant(owned, installed.clientId, "$notes#read"))
    val broad = formToken(openGrant(owned, installed.clientId, "$notes#read $diary#write"))

    val widened = submitGrant(owned, narrow, installed.clientId, listOf("$notes#read", "$diary#write"))
    assertEquals("invalid_scope", query(widened)["error"], widened.responseBody)
    assertTrue(serviceClientStore.find(owned.pod.hosted.id, installed.clientId)!!.scopes.isEmpty())

    val granted = submitGrant(owned, broad, installed.clientId, listOf("$notes#read", "$diary#write"))
    assertEquals("granted", query(granted)["result"], granted.responseBody)
  }

  @Test
  fun `an owner signed in under a linked alias grants the service its contexts`() {
    val owned = ownedPod()
    val notes = owned.context("notes")
    val installed = install(owned)
    val alias = "https://id.test/oidc/${org.sempods.commons.tests.TestUtil.randomId()}"
    val cookie = signIn(owned.pod.name, alias, alsoKnownAs = listOf(owned.webId)).cookie

    val page = openGrant(owned, installed.clientId, "$notes#read", cookie = cookie)
    assertEquals(200, page.statusCode, page.responseBody)
    val granted = submitGrant(owned, formToken(page), installed.clientId, listOf("$notes#read"), cookie = cookie)

    assertEquals("granted", query(granted)["result"], granted.responseBody)
    assertEquals(listOf(notes), contextsReachableBy(owned.pod, serviceToken(owned, installed.clientId, installed.secret)))
  }

  // ── Two authorities ─────────────────────────────────────────────────────────

  @Test
  fun `an installer bearer cannot list, rotate or revoke an existing registration`() {
    // The escalation #124 named: an installer approved for one service must not reach another
    // that already holds more, rotate its secret and inherit its access.
    val owned = ownedPod()
    val existing = install(owned)
    val installer = approveInstallation(owned)

    val listed = http.prepareGet(serviceClientsUrl(owned)).addHeader("Authorization", "Bearer $installer").execute()
    val rotated = http.preparePost("${serviceClientsUrl(owned)}/${enc(existing.clientId)}/secret")
      .addHeader("Authorization", "Bearer $installer").execute()
    val revoked = http.prepareDelete("${serviceClientsUrl(owned)}/${enc(existing.clientId)}")
      .addHeader("Authorization", "Bearer $installer").execute()

    for (response in listOf(listed, rotated, revoked)) {
      assertEquals(403, response.statusCode, response.responseBody)
      assertTrue("insufficient_scope" in checkNotNull(response.getHeader("WWW-Authenticate")))
    }
    // The secret the service holds still authenticates: `invalid_scope`, not `invalid_client`.
    val stillItsOwn = mint(owned, existing.clientId, existing.secret)
    assertTrue("invalid_scope" in stillItsOwn.responseBody, stillItsOwn.responseBody)
  }

  @Test
  fun `one app holds an installation and a management authority at once`() {
    // Approving the second must not withdraw the first, in either order.
    val owned = ownedPod()
    val installer = approveInstallation(owned)
    val manager = approveManagement(owned)

    val registered = http.preparePost(registerUrl(owned))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $installer")
      .setBody(installationBody)
      .execute()
    assertEquals(201, registered.statusCode, registered.responseBody)
    assertTrue(listServiceClients(owned, manager).any { it["client_id"] == json(registered)["client_id"] })
  }

  @Test
  fun `a management bearer does not register and reaches no data`() {
    val owned = ownedPod()
    owned.context("notes")
    val manager = approveManagement(owned)

    val registered = http.preparePost(registerUrl(owned))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $manager")
      .setBody(installationBody)
      .execute()
    assertEquals(403, registered.statusCode, registered.responseBody)
    assertEquals(emptyList(), contextsReachableBy(owned.pod, manager))
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
    val response = authorizePage(owned, "$SERVICE_CLIENTS_INSTALL_SCOPE $SERVICE_CLIENTS_MANAGE_SCOPE")
    assertEquals(303, response.statusCode, response.responseBody)
    assertEquals("invalid_scope", query(response)["error"])
  }

  @Test
  fun `a management bearer sees only its own pod`() {
    val mine = ownedPod()
    val theirs = ownedPod()
    val theirService = install(theirs)
    val manager = approveManagement(mine)

    val listed = listServiceClients(mine, manager)
    assertFalse(listed.any { it["client_id"] == theirService.clientId })

    val crossPod = http.prepareGet(serviceClientsUrl(theirs)).addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(401, crossPod.statusCode, crossPod.responseBody)
  }

  @Test
  fun `an operator-provisioned client is listed and not changed`() {
    val owned = ownedPod()
    val appRoot = "${podBase(owned)}/_system/contexts/apps/backend"
    serviceClientStore.register(owned.pod.hosted, "backend", setOf("$appRoot#manage"), label = "backend")
    val manager = approveManagement(owned)

    val listed = listServiceClients(owned, manager).single { it["client_id"] == "backend" }
    assertEquals("provisioned", listed["origin"])
    val rotated = http.preparePost("${serviceClientsUrl(owned)}/backend/secret")
      .addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(403, rotated.statusCode, rotated.responseBody)
  }

  @Test
  fun `a management bearer from an owner signed in under a linked alias works`() {
    val owned = ownedPod()
    val alias = "https://id.test/oidc/${org.sempods.commons.tests.TestUtil.randomId()}"
    val manager = approveManagement(owned, signedInAs = alias, alsoKnownAs = listOf(owned.webId))

    val listed = http.prepareGet(serviceClientsUrl(owned)).addHeader("Authorization", "Bearer $manager").execute()
    assertEquals(200, listed.statusCode, listed.responseBody)
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
    val existing = install(owned)

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
    val existing = install(owned)
    val someoneElsesApp = mintScopedToken(owned.pod.name, scopes = emptyList(), webId = "https://id.test/someone-else")
    val formerOwner = approveManagement(owned)
    podDao.setOwner(checkNotNull(owned.pod.id), webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email))

    for (bearer in listOf(someoneElsesApp, formerOwner)) {
      for (route in managementRoutes(owned, existing.clientId)) {
        val response = route(bearer)
        assertEquals(403, response.statusCode, response.responseBody)
        val challenge = checkNotNull(response.getHeader("WWW-Authenticate"))
        assertTrue("error=\"insufficient_scope\"" in challenge, challenge)
        assertTrue("resource_metadata=\"${podBase(owned)}/.well-known/oauth-protected-resource\"" in challenge, challenge)
      }
    }
    assertSecretStands(owned, existing)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private inner class Owned(val pod: PodDbo, val webId: String) {
    fun context(path: String): String {
      val uri = URI("${podBase(this)}/${SempodsUriBuilder.CONTEXT_PATH_PREFIX}$path")
      podFacade.createContext(podName = pod.name, contextUri = uri, public = false, label = path, description = null)
      return uri.toString()
    }
  }

  private data class Installed(val clientId: String, val secret: String, val issuedAt: Long)

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    val pod = sempodsTestFactory.newPod(ownerUser = owner)
    return Owned(pod, webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private fun podBase(owned: Owned) = "${SempodsModule.config.apiBaseUrl}${owned.pod.name}"
  private fun serviceClientsUrl(owned: Owned) = "${podBase(owned)}/_system/auth/service-clients"
  private fun grantsUrl(owned: Owned, clientId: String) = "${serviceClientsUrl(owned)}/${enc(clientId)}/grants"
  private fun grantUrl(owned: Owned) = "${podBase(owned)}/_system/auth/grant"
  private fun registerUrl(owned: Owned) = "${podBase(owned)}/_system/auth/register"
  private fun tokenUrl(owned: Owned) = "${podBase(owned)}/_system/auth/token"

  private fun authorizePage(
    owned: Owned,
    scope: String,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): TestHttpResponse = http.prepareGet("${podBase(owned)}/_system/auth/authorize")
    .addQueryParam("response_type", "code")
    .addQueryParam("client_id", installerClientId)
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
        "client_id=${enc(installerClientId)}&redirect_uri=${enc(redirectUri)}" +
          "&state=privileged&csrf=${enc(formToken(page))}&scope=${enc(scope)}",
      )
      .setFollowRedirect(false).execute()
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val code = checkNotNull(query(submitted)["code"]) { "no code: ${submitted.getHeader("Location")}" }
    val exchanged = postForm(
      tokenUrl(owned),
      "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirectUri)}&client_id=${enc(installerClientId)}",
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
        "client_id=${enc(installerClientId)}&redirect_uri=${enc(redirectUri)}" +
          "&state=privileged&csrf=${enc(formToken(page))}&action=disconnect",
      )
      .setFollowRedirect(false).execute()
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    return submitted
  }

  private fun approveInstallation(owned: Owned): String =
    approvePrivileged(owned, SERVICE_CLIENTS_INSTALL_SCOPE)["access_token"] as String

  private fun approveManagementTokens(owned: Owned) = approvePrivileged(owned, SERVICE_CLIENTS_MANAGE_SCOPE)

  private fun approveManagement(
    owned: Owned,
    signedInAs: String = owned.webId,
    alsoKnownAs: List<String> = emptyList(),
  ): String = approvePrivileged(owned, SERVICE_CLIENTS_MANAGE_SCOPE, signedInAs, alsoKnownAs)["access_token"] as String

  private fun install(owned: Owned): Installed {
    val registered = http.preparePost(registerUrl(owned))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer ${approveInstallation(owned)}")
      .setBody(installationBody)
      .execute()
    assertEquals(201, registered.statusCode, registered.responseBody)
    val body = json(registered)
    return Installed(
      clientId = body["client_id"] as String,
      secret = body["client_secret"] as String,
      issuedAt = (body["client_id_issued_at"] as Number).toLong(),
    )
  }

  private fun openGrant(
    owned: Owned,
    serviceClient: String,
    scope: String,
    cookie: String = signIn(owned.pod.name, owned.webId).cookie,
  ): TestHttpResponse = http.prepareGet(grantUrl(owned))
    .addQueryParam("client_id", installerClientId)
    .addQueryParam("redirect_uri", redirectUri)
    .addQueryParam("state", "grant-1")
    .addQueryParam("service_client", serviceClient)
    .addQueryParam("scope", scope)
    .addHeader("Cookie", cookie)
    .setFollowRedirect(false).execute()

  private fun submitGrant(
    owned: Owned,
    token: String,
    serviceClient: String,
    scopes: List<String>,
    action: String = "grant",
    cookie: String = signIn(owned.pod.name, owned.webId).cookie,
  ): TestHttpResponse = http.preparePost(grantUrl(owned))
    .addHeader("Content-Type", "application/x-www-form-urlencoded")
    .addHeader("Cookie", cookie)
    .setBody(
      "csrf=${enc(token)}&service_client=${enc(serviceClient)}&action=$action" +
        scopes.joinToString("") { "&scope=${enc(it)}" },
    )
    .setFollowRedirect(false).execute()

  private fun formToken(page: TestHttpResponse): String =
    Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)?.groupValues?.get(1)
      ?: error("no form token in the rendered page: ${page.statusCode} ${page.responseBody.take(300)}")

  private fun mint(owned: Owned, clientId: String, secret: String): TestHttpResponse =
    http.preparePost(tokenUrl(owned))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(clientId, secret))
      .setBody("grant_type=client_credentials")
      .execute()

  /** The secret still authenticates, so nothing rotated or revoked it: `invalid_scope`, not `invalid_client`. */
  private fun assertSecretStands(owned: Owned, installed: Installed) {
    val minted = mint(owned, installed.clientId, installed.secret)
    assertTrue("invalid_scope" in minted.responseBody, minted.responseBody)
  }

  private fun serviceToken(owned: Owned, clientId: String, secret: String): String {
    val minted = mint(owned, clientId, secret)
    assertEquals(200, minted.statusCode, minted.responseBody)
    return json(minted)["access_token"] as String
  }

  /** List, rotate, remove grants and revoke [clientId], each sent with a bearer or without one. */
  private fun managementRoutes(owned: Owned, clientId: String): List<(String?) -> TestHttpResponse> {
    val registration = "${serviceClientsUrl(owned)}/${enc(clientId)}"
    fun TestHttpRequest.send(bearer: String?) =
      apply { if (bearer != null) addHeader("Authorization", "Bearer $bearer") }.execute()
    return listOf(
      { bearer -> http.prepareGet(serviceClientsUrl(owned)).send(bearer) },
      { bearer -> http.preparePost("$registration/secret").send(bearer) },
      { bearer -> http.prepareDelete("${grantsUrl(owned, clientId)}?scope=${enc("${podBase(owned)}/_system/contexts/notes#read")}").send(bearer) },
      { bearer -> http.prepareDelete(registration).send(bearer) },
    )
  }

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

  private fun read(resource: URI, bearer: String): TestHttpResponse = http.prepareGet(resource.toString())
    .addHeader("Accept", "application/ld+json")
    .addHeader("Authorization", "Bearer $bearer")
    .execute()

  /** The contexts a bearer can reach, as the pod's own registry listing reports them. */
  private fun contextsReachableBy(pod: PodDbo, accessToken: String): List<String> {
    val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts")
      .addHeader("Accept", "application/json")
      .addHeader("Authorization", "Bearer $accessToken")
      .execute()
    assertEquals(200, response.statusCode, response.responseBody)
    return (json(response)["contexts"] as List<Map<String, Any?>>).map { it["context_iri"] as String }
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
