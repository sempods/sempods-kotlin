package org.sempods.api.pod.system.contexts

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.net.UrlUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.grants.CONTEXTS_MANAGE_SCOPE
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.mongo.persist.PodDbo
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `contexts:manage` through the owner's own dialog: the authority a program needs to create and
 * delete contexts beyond what it was granted. `PodContextsEndpointHttpTest` covers what the bearer
 * may do once it exists, with the authority written directly.
 */
@Suppress("UNCHECKED_CAST")
class PodContextsManagementHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val clientId = "did:web:localhost%3A5173"
  private val redirectUri = "http://localhost:5173/callback"
  private val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
  private val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

  @Test
  fun `the owner approves the authority in its own dialog, and the bearer creates and deletes any context`() {
    val owned = ownedPod()
    val page = authorizePage(owned, CONTEXTS_MANAGE_SCOPE)
    assertEquals(200, page.statusCode, page.responseBody)
    val toggle = Regex("""<input[^>]*id="contextsManagementToggle"[^>]*>""").find(page.responseBody)?.value
    assertTrue(toggle != null && CONTEXTS_MANAGE_SCOPE in toggle, "the dialog offers the scope: ${page.responseBody.take(300)}")
    assertFalse("checked" in toggle, "offered, never pre-ticked: $toggle")

    val tokens = approve(owned, page)
    assertNull(tokens["refresh_token"], "the authority is an hour and no more")
    assertEquals(CONTEXTS_MANAGE_SCOPE, tokens["scope"])
    val bearer = tokens["access_token"] as String

    val created = http.preparePut(contextUrl(owned, "projects"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("{}")
      .execute()
    assertEquals(201, created.statusCode, created.responseBody)
    val deleted = http.prepareDelete(contextUrl(owned, "projects"))
      .addHeader("Authorization", "Bearer $bearer")
      .execute()
    assertEquals(204, deleted.statusCode, deleted.responseBody)
  }

  @Test
  fun `only the owner is asked`() {
    val owned = ownedPod()
    val stranger = webIdUriDeriver.deriveFromEmail(sempodsTestFactory.newOwner().email)
    val response = authorizePage(owned, CONTEXTS_MANAGE_SCOPE, signedInAs = stranger)
    assertEquals(303, response.statusCode, response.responseBody)
    assertEquals("invalid_scope", query(response)["error"])
  }

  @Test
  fun `the authority is granted at the dialog, never silently`() {
    val owned = ownedPod()
    val response = authorizePage(owned, CONTEXTS_MANAGE_SCOPE, prompt = "none")
    assertEquals(303, response.statusCode, response.responseBody)
    assertEquals("consent_required", query(response)["error"])
  }

  @Test
  fun `the authority stands alone`() {
    val owned = ownedPod()
    val context = "${podBase(owned)}/_system/contexts/notes#read"
    for (scope in listOf("$CONTEXTS_MANAGE_SCOPE $SERVICE_CLIENTS_MANAGE_SCOPE", "$CONTEXTS_MANAGE_SCOPE $context")) {
      val response = authorizePage(owned, scope)
      assertEquals(303, response.statusCode, "$scope: ${response.responseBody}")
      assertEquals("invalid_scope", query(response)["error"], scope)
    }
  }

  @Test
  fun `the authority approved on its own is disconnected in the ordinary dialog`() {
    // It writes no grant, so a dialog that asked only about grants offered no way to end it early.
    val owned = ownedPod()
    val bearer = approve(owned, authorizePage(owned, CONTEXTS_MANAGE_SCOPE))["access_token"] as String

    val page = authorizePage(owned, PUBLIC_READ_SCOPE)
    assertTrue("disconnectBtn" in page.responseBody, "the authority is something this app holds")
    val csrf = Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)?.groupValues?.get(1)
      ?: error("no form token in the rendered page")
    val disconnected = http.preparePost("${podBase(owned)}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(owned.pod.name, owned.webId).cookie)
      .setBody("client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}&state=contexts&csrf=${enc(csrf)}&action=disconnect")
      .setFollowRedirect(false).execute()
    assertEquals("app disconnected", query(disconnected)["error_description"], disconnected.getHeader("Location"))

    val created = http.preparePut(contextUrl(owned, "projects"))
      .addHeader("Content-Type", "application/json")
      .addHeader("Authorization", "Bearer $bearer")
      .setBody("{}")
      .execute()
    assertPodBearerChallenge(created, owned.pod.name)
  }

  // ── Fixture ─────────────────────────────────────────────────────────────────

  private class Owned(val pod: PodDbo, val webId: String)

  private fun ownedPod(): Owned {
    val owner = sempodsTestFactory.newOwner()
    return Owned(sempodsTestFactory.newPod(ownerUser = owner), webIdUriDeriver.deriveFromEmail(owner.email))
  }

  private fun podBase(owned: Owned) = "${SempodsModule.config.apiBaseUrl}${owned.pod.name}"
  private fun contextUrl(owned: Owned, path: String) = "${podBase(owned)}/_system/contexts/$path"

  private fun authorizePage(
    owned: Owned,
    scope: String,
    signedInAs: String = owned.webId,
    prompt: String? = null,
  ): TestHttpResponse {
    val request = http.prepareGet("${podBase(owned)}/_system/auth/authorize")
      .addQueryParam("response_type", "code")
      .addQueryParam("client_id", clientId)
      .addQueryParam("redirect_uri", redirectUri)
      .addQueryParam("state", "contexts")
      .addQueryParam("scope", scope)
      .addQueryParam("code_challenge", codeChallenge)
      .addQueryParam("code_challenge_method", "S256")
    prompt?.let { request.addQueryParam("prompt", it) }
    return request.addHeader("Cookie", signIn(owned.pod.name, signedInAs).cookie).setFollowRedirect(false).execute()
  }

  /** Ticks the scope on [page], submits it and redeems the code — the dialog as a browser runs it. */
  private fun approve(owned: Owned, page: TestHttpResponse): Map<String, Any?> {
    val csrf = Regex("""name="csrf" value="([^"]+)"""").find(page.responseBody)?.groupValues?.get(1)
      ?: error("no form token in the rendered page")
    val submitted = http.preparePost("${podBase(owned)}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", signIn(owned.pod.name, owned.webId).cookie)
      .setBody(
        "client_id=${enc(clientId)}&redirect_uri=${enc(redirectUri)}" +
          "&state=contexts&csrf=${enc(csrf)}&scope=${enc(CONTEXTS_MANAGE_SCOPE)}",
      )
      .setFollowRedirect(false).execute()
    assertEquals(303, submitted.statusCode, submitted.responseBody)
    val code = checkNotNull(query(submitted)["code"]) { "no code: ${submitted.getHeader("Location")}" }
    val exchanged = http.preparePost("${podBase(owned)}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(
        "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirectUri)}" +
          "&client_id=${enc(clientId)}&code_verifier=${enc(codeVerifier)}",
      )
      .execute()
    assertEquals(200, exchanged.statusCode, exchanged.responseBody)
    return JsonMappers.default().readValue(exchanged.responseBody, Map::class.java) as Map<String, Any?>
  }

  /** The query of a redirect's `Location`, decoded. */
  private fun query(response: TestHttpResponse): Map<String, String> {
    val location = checkNotNull(response.getHeader("Location")) { "no redirect: ${response.statusCode} ${response.responseBody}" }
    return UrlUtil.queryParams(URI(location).rawQuery)
  }
}
