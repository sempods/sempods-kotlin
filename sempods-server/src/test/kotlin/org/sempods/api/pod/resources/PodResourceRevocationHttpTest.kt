package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.commons.tests.TestUtil
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end proof that an owner-level grant revocation reaches an app that already holds a live
 * access token.
 *
 * This is the gap the revocation cascade closes: the request path resolves context permissions
 * from the app-delegated store alone, so without the cascade the app would keep its access after
 * the pod owner took the underlying WebID grant away. Everything here goes through real HTTP —
 * consent, token exchange, resource read and write — so a regression cannot hide behind a unit
 * seam.
 */
class PodResourceRevocationHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Inject
  private lateinit var podWebIdGrantsDao: PodWebIdGrantsDao

  @Inject
  private lateinit var podGrantsFacade: PodGrantsFacade

  @Inject
  private lateinit var webIdUriDeriver: WebIdUriDeriver

  private val httpClient by lazy { http.followingRedirects }

  private val testClientId = "did:web:localhost%3A5173"
  private val testRedirectUri = "http://localhost:5173/callback"

  @Test
  fun `owner-level grant revocation stops an already-consented app on the next request`() {
    val pod = sempodsTestFactory.newPod()
    val user = sempodsTestFactory.newOwner()
    val webId = webIdUriDeriver.deriveFromEmail(checkNotNull(user.email))

    // A private context — the pod's default public one would mask the revocation behind
    // anonymous read access.
    val contextPath = "private/reports"
    val contextUri = URI("${SempodsModule.config.apiBaseUrl}${pod.name}/$contextPath")
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = contextUri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )

    // The pod owner grants the person read+write; the person delegates both to the app.
    podWebIdGrantsDao.addGrants(
      podId = checkNotNull(pod.id),
      webId = webId,
      grants = listOf("$contextUri#read", "$contextUri#write"),
      grantedBy = pod.owner,
    )
    val accessToken = authorizeAndExchange(pod, webId, listOf("$contextUri#read", "$contextUri#write"))

    val eventId = TestUtil.randomId()
    val eventUri = sempodsTestFactory.eventUri(podName = pod.name, eventId = eventId)
    val resourceUrl = "${SempodsModule.config.apiBaseUrl}${pod.name}/events/$eventId"
    val nQuads = """
      <$eventUri> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <https://schema.org/Event> <$contextUri> .
      <$eventUri> <https://schema.org/name> "name-${TestUtil.randomId()}" <$contextUri> .
    """.trimIndent()

    // 201 on create, 200 on overwrite — this one creates.
    assertTrue(
      put(resourceUrl, contextPath, accessToken, nQuads).statusCode in setOf(200, 201),
      "write before revocation",
    )
    assertEquals(200, get(resourceUrl, accessToken).statusCode, "read before revocation")

    // The owner takes the grant back. The app is not involved and its token is untouched.
    podGrantsFacade.revokeWebIdGrants(pod, webId, listOf("$contextUri#read", "$contextUri#write"))

    // Same token, same requests. Writes are refused outright; reads simply stop seeing the
    // context — `authenticate` builds `restrictedContexts` and the read path filters by it, so an
    // unreadable resource is "not found" rather than "forbidden".
    assertEquals(403, put(resourceUrl, contextPath, accessToken, nQuads).statusCode, "write after revocation")
    assertEquals(404, get(resourceUrl, accessToken).statusCode, "read after revocation")

    // Second signal, independent of the resource layer: the context is gone from the caller's
    // effective-permission listing.
    val contexts = get(
      url = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts",
      token = accessToken,
      accept = "application/json",
    )
    assertEquals(200, contexts.statusCode)
    assertTrue(
      !contexts.responseBody.contains(contextUri.toString()),
      "revoked context must not be listed any more, got: ${contexts.responseBody}",
    )
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Runs the real consent submission and code exchange, returning the pod-issued access token. */
  private fun authorizeAndExchange(pod: PodDbo, webId: String, grants: List<String>): String {
    val browser = signIn(pod.name, webId)
    val consent = http
      .preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/authorize/consent")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Cookie", browser.cookie)
      .setBody(
        "client_id=${enc(testClientId)}" +
          "&redirect_uri=${enc(testRedirectUri)}" +
          "&state=revocation" +
          "&csrf=${enc(browser.csrf)}" +
          grants.joinToString("") { "&scope=${enc(it)}" }
      )
      .setFollowRedirect(false)
      .execute()

    assertEquals(303, consent.statusCode, "consent should redirect with an auth code")
    val location = consent.getHeader("Location")
    assertNotNull(location)
    val code = Regex("[?&]code=([^&]+)").find(location)?.groupValues?.get(1)
      ?: error("no auth code in redirect: $location")

    val token = http
      .preparePost("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/auth/token")
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody(
        "grant_type=authorization_code" +
          "&code=${enc(code)}" +
          "&redirect_uri=${enc(testRedirectUri)}" +
          "&client_id=${enc(testClientId)}"
      )
      .execute()

    assertEquals(200, token.statusCode, "token exchange failed: ${token.responseBody}")
    @Suppress("UNCHECKED_CAST")
    val body = JsonMappers.default().readValue(token.responseBody, Map::class.java) as Map<String, Any?>
    return checkNotNull(body["access_token"] as? String) { "no access_token in ${token.responseBody}" }
  }

  private fun put(url: String, contextPath: String, token: String, nQuads: String) =
    httpClient.preparePut("$url?context=${enc(contextPath)}")
      .addHeader("Content-Type", "application/n-quads")
      .addHeader("Authorization", "Bearer $token")
      .setBody(nQuads)
      .execute()

  private fun get(url: String, token: String, accept: String = "application/ld+json") =
    httpClient.prepareGet(url)
      .addHeader("Accept", accept)
      .addHeader("Authorization", "Bearer $token")
      .execute()

  private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
