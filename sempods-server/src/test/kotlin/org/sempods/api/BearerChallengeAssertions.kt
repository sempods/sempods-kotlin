package org.sempods.api

import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts the pod's answer to a missing or rejected bearer: `401` with a Bearer `invalid_token`
 * challenge whose `resource_metadata` is exactly `{pod}/.well-known/oauth-protected-resource`
 * (`SPS-AUTH-064`). `{pod}` is built the way `SempodsBaseEndpoint.buildBearerChallenge` builds it.
 */
internal fun assertPodBearerChallenge(response: TestHttpResponse, podName: String) {
  assertEquals(401, response.statusCode, response.responseBody)
  val challenge = assertNotNull(response.getHeader("WWW-Authenticate"), "a 401 carries WWW-Authenticate")
  assertTrue(challenge.startsWith("Bearer ") && "error=\"invalid_token\"" in challenge, challenge)
  assertEquals(
    "${SempodsModule.config.apiBaseUrl}$podName/.well-known/oauth-protected-resource",
    Regex("""resource_metadata="([^"]*)"""").find(challenge)?.groupValues?.get(1),
    challenge,
  )
}
