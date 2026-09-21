package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.auth.core.OAuthErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit — the four shapes the token endpoint answers in, with no server and no store.
 *
 * What it pins is the part a protocol library would decide differently: which members are present
 * at all, and which headers ride along. The HTTP suites assert the same things end to end; these
 * cases are what says *why* a byte is the way it is when one of them goes red.
 */
class PodTokenResponsesTest {

  @Test
  fun `a user token states its scope and its refresh token`() {
    val response = PodTokenResponses.tokens(
      accessToken = "at",
      expiresInSeconds = 3600,
      scope = "public-read",
      refreshToken = "rt",
    )

    assertEquals(200, response.status)
    assertEquals(
      mapOf(
        "access_token" to "at",
        "token_type" to "Bearer",
        "expires_in" to 3600L,
        "scope" to "public-read",
        "refresh_token" to "rt",
      ),
      response.body(),
    )
  }

  @Test
  fun `a bearer carrying no feature scope names no scope member at all`() {
    val body = PodTokenResponses.tokens("at", 3600, scope = null, refreshToken = "rt").body()

    assertFalse("scope" in body, "an empty scope is not a scope RFC 6749 3.3 lets a response name")
    assertEquals("rt", body["refresh_token"])
  }

  @Test
  fun `a service token states an empty scope rather than omitting it`() {
    val body = PodTokenResponses.tokens("at", 600, scope = "").body()

    assertEquals("", body["scope"])
    assertFalse("refresh_token" in body, "a client-credentials answer hands back no refresh token")
  }

  @Test
  fun `no refresh token means an absent member, never a null one`() {
    val body = PodTokenResponses.tokens("at", 3600, scope = "public-read").body()

    assertFalse("refresh_token" in body)
    assertNull(body["refresh_token"])
  }

  @Test
  fun `every answer carries the cache rules and the JSON type`() {
    val answers = listOf(
      PodTokenResponses.tokens("at", 3600, scope = null),
      PodTokenResponses.error(OAuthErrorCode.INVALID_GRANT, "nope"),
      PodTokenResponses.clientAuthenticationRequired(realm = "alice", description = "nope"),
      PodTokenResponses.rateLimited(),
    )

    answers.forEach { response ->
      assertEquals(MediaType.APPLICATION_JSON_TYPE, response.mediaType)
      assertEquals("no-store", response.getHeaderString("Cache-Control"))
      assertEquals("no-cache", response.getHeaderString("Pragma"))
    }
  }

  @Test
  fun `a refusal names the code and the description`() {
    val response = PodTokenResponses.error(OAuthErrorCode.INVALID_SCOPE, "requested scopes not covered")

    assertEquals(400, response.status)
    assertEquals(
      """{"error":"invalid_scope","error_description":"requested scopes not covered"}""",
      response.entity,
    )
  }

  @Test
  fun `a client that failed to authenticate is told which scheme to try`() {
    val response = PodTokenResponses.clientAuthenticationRequired(
      realm = "alice",
      description = "unknown client_id or invalid secret",
    )

    assertEquals(401, response.status)
    assertEquals("""Basic realm="alice"""", response.getHeaderString("WWW-Authenticate"))
    assertEquals(
      """{"error":"invalid_client","error_description":"unknown client_id or invalid secret"}""",
      response.entity,
    )
  }

  @Test
  fun `a caller over its budget is told to slow down and how long to wait`() {
    val response = PodTokenResponses.rateLimited()

    assertEquals(429, response.status)
    assertEquals("60", response.getHeaderString("Retry-After"))
    assertTrue((response.entity as String).contains(""""error":"slow_down""""))
  }

  @Suppress("UNCHECKED_CAST")
  private fun Response.body(): Map<String, Any> = entity as Map<String, Any>
}
