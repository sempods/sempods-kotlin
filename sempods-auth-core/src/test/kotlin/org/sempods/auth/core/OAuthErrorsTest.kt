package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthErrorsTest {

  @Test
  fun `only a refusal upstream is a refusal here, and the upstream code stays in the description`() {
    val cases = listOf(
      Triple("access_denied", null, UpstreamError(OAuthErrorCode.ACCESS_DENIED, "access_denied")),
      Triple("user_cancelled_authorize", " ", UpstreamError(OAuthErrorCode.ACCESS_DENIED, "user_cancelled_authorize")),
      Triple("temporarily_unavailable", "busy", UpstreamError(OAuthErrorCode.TEMPORARILY_UNAVAILABLE, "temporarily_unavailable: busy")),
      Triple("invalid_scope", "invalid_scope", UpstreamError(OAuthErrorCode.SERVER_ERROR, "invalid_scope")),
      Triple("something_new", "what", UpstreamError(OAuthErrorCode.SERVER_ERROR, "something_new: what")),
    )

    for ((error, description, expected) in cases) {
      assertEquals(expected, OAuthErrors.fromUpstream(error, description), error)
    }
  }

  @Test
  fun `a provider's text keeps to the character set a redirect may carry`() {
    // RFC 6749 §4.1.2.1 leaves out `"` and `\`, and everything outside printable ASCII.
    assertEquals(
      "access_denied: no thanks",
      OAuthErrors.fromUpstream("access_denied", "no \"thanks\"\n").description,
    )
  }
}
