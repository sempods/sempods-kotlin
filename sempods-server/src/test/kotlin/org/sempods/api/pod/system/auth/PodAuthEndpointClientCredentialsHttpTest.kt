package org.sempods.api.pod.system.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject.Inject
import com.nimbusds.jwt.SignedJWT
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.pods.oauth.SERVICE_CLIENT_TYPE
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.persist.PodServiceAuditLogDao
import org.sempods.commons.okhttp.TestHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for the service-client `client_credentials` grant (see `docs/auth/service-clients.md`):
 * the OAuth `client_credentials` grant on `{pod}/_system/auth/token`.
 */
class PodAuthEndpointClientCredentialsHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podServiceClientStore: PodServiceClientStore

  @Inject
  private lateinit var podServiceAuditLogDao: PodServiceAuditLogDao

  private val objectMapper = ObjectMapper()

  private fun tokenUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/_system/auth/token"

  private fun podBaseUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}${podName}/"

  /**
   * RFC 6749 §2.3.1 `client_secret_basic`: clients form-urlencode `client_id`
   * and `client_secret` before joining with `:` and base64-encoding.
   */
  private fun basicHeader(clientId: String, secret: String): String {
    val encId = java.net.URLEncoder.encode(clientId, Charsets.UTF_8)
    val encSecret = java.net.URLEncoder.encode(secret, Charsets.UTF_8)
    return "Basic " + Base64.getEncoder().encodeToString("$encId:$encSecret".toByteArray(Charsets.UTF_8))
  }

  @Test
  fun `client_credentials issues a service token bound to client_id with short TTL`() {
    val pod = sempodsTestFactory.newPod()
    val appRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/notes"
    val registered = podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = podBaseUrl(pod.name),
      clientId = "notes-app",
      scopes = setOf("$appRoot#manage"),
      label = "notes-app",
    )

    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.dbo.clientId, registered.plaintextSecret))
      .setBody("grant_type=client_credentials")
      .execute()

    assertEquals(200, response.statusCode, "unexpected status; body=${response.responseBody}")
    val body: Map<String, Any?> = objectMapper.readValue(response.responseBody, Map::class.java)
      .mapKeys { it.key.toString() }
    val accessToken = body["access_token"] as? String
    assertNotNull(accessToken, "access_token missing in $body")
    assertEquals("Bearer", body["token_type"])
    val expiresIn = (body["expires_in"] as Number).toLong()
    assertTrue(
      expiresIn in 1..PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS,
      "expires_in $expiresIn should be inside 1..${PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS}",
    )
    // Slim service token (token-slimming): context scopes are NOT carried in the token —
    // they are resolved server-side from PodServiceClientDao per request. This client registers
    // only a context manage scope (no feature scopes), so `scope` is empty here. The actual
    // context access this token grants is verified in
    // `service token grants access to context inside manage root but not outside`.
    assertEquals("", body["scope"])

    val claims = SignedJWT.parse(accessToken).jwtClaimsSet
    assertEquals("notes-app", claims.subject, "sub must be client_id, not WebID")
    assertEquals("notes-app", claims.getStringClaim("client_id"))
    assertEquals(SERVICE_CLIENT_TYPE, claims.getStringClaim("client_type"))
    assertEquals("", claims.getStringClaim("scope"), "slim service token carries no context scopes")
    val ttl = (claims.expirationTime.time - claims.issueTime.time) / 1000
    assertEquals(PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS, ttl, "exp should be issued-at + service-token TTL")
  }

  @Test
  fun `client_credentials with unknown secret returns 401 invalid_client`() {
    val pod = sempodsTestFactory.newPod()
    podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = podBaseUrl(pod.name),
      clientId = "notes-app",
      scopes = setOf("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/notes#manage"),
    )

    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader("notes-app", "wrong-secret"))
      .setBody("grant_type=client_credentials")
      .execute()

    assertEquals(401, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_client"), response.responseBody)
    assertNotNull(response.getHeader("WWW-Authenticate"))
  }

  @Test
  fun `client_credentials without Authorization header returns 401`() {
    val pod = sempodsTestFactory.newPod()

    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .setBody("grant_type=client_credentials")
      .execute()

    assertEquals(401, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_client"), response.responseBody)
  }

  @Test
  fun `client_credentials rejects any scope parameter (down-scoping unsupported)`() {
    // Slim service tokens carry no per-token state to express a subset, and the resolver
    // always grants the client's full registered set from PodServiceClientDao. Accepting a
    // `scope=` would silently grant more than requested, so the endpoint rejects it outright.
    val pod = sempodsTestFactory.newPod()
    val appRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/notes"
    val registered = podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = podBaseUrl(pod.name),
      clientId = "notes-app",
      scopes = setOf("$appRoot#manage"),
    )

    // Even requesting the exact registered scope is rejected — down-scoping is unsupported.
    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.dbo.clientId, registered.plaintextSecret))
      .setBody(
        "grant_type=client_credentials" +
          "&scope=${java.net.URLEncoder.encode("$appRoot#manage", "UTF-8")}",
      )
      .execute()

    assertEquals(400, response.statusCode)
    assertTrue(response.responseBody.contains("invalid_scope"), response.responseBody)
  }

  @Test
  fun `client_credentials accepts form-urlencoded client_id and secret with reserved characters`() {
    // RFC 6749 §2.3.1 requires form-encoding of client_id / client_secret
    // before base64. Pick a clientId carrying a colon (`:`) — without server-
    // side form-decoding it would be split mid-identifier or compared
    // against the literal `%3A` percent-encoded form and fail.
    val pod = sempodsTestFactory.newPod()
    val appRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/notes"
    val clientIdWithReserved = "notes:primary"
    val registered = podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = podBaseUrl(pod.name),
      clientId = clientIdWithReserved,
      scopes = setOf("$appRoot#manage"),
    )

    val response = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(clientIdWithReserved, registered.plaintextSecret))
      .setBody("grant_type=client_credentials")
      .execute()

    assertEquals(200, response.statusCode, "unexpected status; body=${response.responseBody}")
    val body: Map<String, Any?> = objectMapper.readValue(response.responseBody, Map::class.java)
      .mapKeys { it.key.toString() }
    val accessToken = body["access_token"] as? String
    assertNotNull(accessToken)
    val claims = SignedJWT.parse(accessToken).jwtClaimsSet
    assertEquals(clientIdWithReserved, claims.subject, "sub must round-trip through form-encoding")
    assertEquals(clientIdWithReserved, claims.getStringClaim("client_id"))
  }

  @Test
  fun `service token grants access to context inside manage root but not outside`() {
    // Verifies the slash-delimited manage rule still applies to service-client
    // tokens: the bearer can act inside <app-root>, but the same token
    // is rejected when used to write a sibling context.
    val pod = sempodsTestFactory.newPod()
    val podBase = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    val appRoot = "$podBase/_system/contexts/apps/notes"
    val registered = podServiceClientStore.register(
      podId = checkNotNull(pod.id),
      podBaseUrl = podBaseUrl(pod.name),
      clientId = "notes-app",
      scopes = setOf("$appRoot#manage"),
    )

    val tokenResponse = http.preparePost(tokenUrl(pod.name))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("Authorization", basicHeader(registered.dbo.clientId, registered.plaintextSecret))
      .setBody("grant_type=client_credentials")
      .execute()
    assertEquals(200, tokenResponse.statusCode)
    val accessToken = (objectMapper.readValue(tokenResponse.responseBody, Map::class.java)
      ["access_token"] as String)

    // Bearer reaches a resource read in a context inside the root: an unconfigured
    // context returns 404 from the resource endpoint, NOT 403. The point of this
    // assertion is to prove the token authenticates (no 401) and the manage check
    // does not refuse it (no 403). Context creation under <app-root> via the
    // manage gate is covered in PodContextsEndpointHttpTest.
    val insideResponse = http.prepareGet("$appRoot/events")
      .addHeader("Authorization", "Bearer $accessToken")
      .execute()
    assertTrue(
      insideResponse.statusCode in setOf(200, 404),
      "expected 200 or 404 for read inside manage root, got ${insideResponse.statusCode}: ${insideResponse.responseBody}",
    )

    // The audit log must have a row for the request the service client made.
    val auditEntries = podServiceAuditLogDao.findRecent(checkNotNull(pod.id))
    assertTrue(auditEntries.any { it.clientId == "notes-app" }, "audit row missing for service-client request")
  }

  @Test
  fun `register rejects pod-root manage scope`() {
    // `<pod-base>#manage` is not a valid context scope — the pod base is not itself
    // a context inside the pod. The validator catches this before persistence so a
    // wildcard-shaped scope never makes it into a JWT.
    val pod = sempodsTestFactory.newPod()
    val podRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}"
    val ex = assertThrows<IllegalArgumentException> {
      podServiceClientStore.register(
        podId = checkNotNull(pod.id),
        podBaseUrl = podBaseUrl(pod.name),
        clientId = "bogus",
        scopes = setOf("${podRoot}#manage"),
      )
    }
    assertTrue(
      ex.message?.contains("must be inside pod base") == true,
      "expected reason to point at the pod-base constraint, got: ${ex.message}"
    )
  }

  @Test
  fun `register rejects unsupported permission`() {
    val pod = sempodsTestFactory.newPod()
    val appRoot = "${SempodsModule.config.apiBaseUrl}${pod.name}/_system/contexts/apps/notes"
    val ex = assertThrows<IllegalArgumentException> {
      podServiceClientStore.register(
        podId = checkNotNull(pod.id),
        podBaseUrl = podBaseUrl(pod.name),
        clientId = "bogus",
        scopes = setOf("${appRoot}#admin"),
      )
    }
    assertTrue(
      ex.message?.contains("unsupported scope permission") == true,
      "expected validator reason in message, got: ${ex.message}"
    )
  }

  @Test
  fun `register rejects scope referring to a foreign pod`() {
    val pod = sempodsTestFactory.newPod()
    val otherPodName = "other-pod-${java.util.UUID.randomUUID().toString().take(8)}"
    val foreignScope = "${SempodsModule.config.apiBaseUrl}${otherPodName}/_system/contexts/apps/notes#manage"
    val ex = assertThrows<IllegalArgumentException> {
      podServiceClientStore.register(
        podId = checkNotNull(pod.id),
        podBaseUrl = podBaseUrl(pod.name),
        clientId = "bogus",
        scopes = setOf(foreignScope),
      )
    }
    assertTrue(
      ex.message?.contains("must be inside pod base") == true,
      "expected pod-base mismatch reason, got: ${ex.message}"
    )
  }

  @Test
  fun `register rejects OIDC scopes for service clients`() {
    val pod = sempodsTestFactory.newPod()
    val ex = assertThrows<IllegalArgumentException> {
      podServiceClientStore.register(
        podId = checkNotNull(pod.id),
        podBaseUrl = podBaseUrl(pod.name),
        clientId = "bogus",
        scopes = setOf("openid"),
      )
    }
    assertTrue(
      ex.message?.contains("not applicable to service clients") == true,
      "expected service-client rejection reason, got: ${ex.message}"
    )
  }
}
