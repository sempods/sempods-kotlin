package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pure unit — the four shapes the token endpoint answers in, with no server and no store.
 *
 * What it pins is the part a protocol library would decide differently: which members are present
 * at all, and which headers ride along. Bodies are compared decoded, because member order and
 * number width are not the contract. The HTTP suites assert the same things end to end; these
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
    assertEquals(cacheRules, response.headerMap())
    assertEquals(
      mapOf(
        "access_token" to "at",
        "token_type" to "Bearer",
        "expires_in" to 3600,
        "scope" to "public-read",
        "refresh_token" to "rt",
      ),
      response.json(),
    )
  }

  @Test
  fun `a bearer carrying no feature scope names no scope member at all`() {
    val body = PodTokenResponses.tokens("at", 3600, scope = null, refreshToken = "rt").json()

    assertEquals(
      setOf("access_token", "token_type", "expires_in", "refresh_token"),
      body.keys,
      "an empty scope is not a scope RFC 6749 3.3 lets a response name",
    )
  }

  @Test
  fun `a service token states an empty scope rather than omitting it`() {
    val body = PodTokenResponses.tokens("at", 600, scope = "").json()

    assertEquals(
      mapOf("access_token" to "at", "token_type" to "Bearer", "expires_in" to 600, "scope" to ""),
      body,
      "a client-credentials answer hands back no refresh token",
    )
  }

  @Test
  fun `no refresh token means an absent member, never a null one`() {
    val body = PodTokenResponses.tokens("at", 3600, scope = "public-read").json()

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
    assertEquals(cacheRules, response.headerMap())
    assertEquals(
      mapOf("error" to "invalid_scope", "error_description" to "requested scopes not covered"),
      response.json(),
    )
  }

  @Test
  fun `a client that failed to authenticate is told which scheme to try`() {
    val response = PodTokenResponses.clientAuthenticationRequired(
      realm = "alice",
      description = "unknown client_id or invalid secret",
    )

    assertEquals(401, response.status)
    assertEquals(cacheRules + ("WWW-Authenticate" to """Basic realm="alice""""), response.headerMap())
    assertEquals(
      mapOf("error" to "invalid_client", "error_description" to "unknown client_id or invalid secret"),
      response.json(),
    )
  }

  @Test
  fun `a caller over its budget is told to slow down and how long to wait`() {
    val response = PodTokenResponses.rateLimited()

    assertEquals(429, response.status)
    assertEquals(cacheRules + ("Retry-After" to "60"), response.headerMap())
    assertEquals(
      mapOf("error" to "slow_down", "error_description" to "too many token requests; retry later"),
      response.json(),
    )
  }

  @Test
  fun `a description keeps to the character set RFC 6749 allows it`() {
    // §5.2 allows printable ASCII except `"` and `\`, so a dash, a quote and a backslash are dropped.
    val response = PodTokenResponses.error(OAuthErrorCode.INVALID_GRANT, """a "quoted" \value — here""")

    assertEquals(400, response.status)
    assertEquals(
      mapOf("error" to "invalid_grant", "error_description" to "a quoted value  here"),
      response.json(),
    )
  }

  /** The headers every answer carries — see [PodTokenResponses]. */
  private val cacheRules = mapOf(
    "Content-Type" to "application/json",
    "Cache-Control" to "no-store",
    "Pragma" to "no-cache",
  )

  /** Every header, one value each — a second value would be a finding of its own. */
  private fun Response.headerMap(): Map<String, String> =
    stringHeaders.mapValues { (_, values) -> values.single() }

  /** The body as a client decodes it. */
  private fun Response.json(): Map<String, Any?> =
    JsonMappers.default().readValue(entity as String, JsonUtil.dynamicTypeRef)
}
