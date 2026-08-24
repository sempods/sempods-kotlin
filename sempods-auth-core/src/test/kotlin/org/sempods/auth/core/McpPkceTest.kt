package org.sempods.auth.core

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpPkceTest {

  private fun challengeFor(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  @Test
  fun `valid S256 verifier matches its challenge`() {
    val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    assertTrue(Pkce.verifyS256(verifier, challengeFor(verifier)))
  }

  @Test
  fun `wrong verifier is rejected`() {
    val challenge = challengeFor("the-real-verifier")
    assertFalse(Pkce.verifyS256("a-different-verifier", challenge))
  }

  @Test
  fun `generated verifier and challenge round-trip (client side)`() {
    val verifier = Pkce.generateVerifier()
    assertTrue(verifier.length in 43..128, "verifier length out of RFC 7636 range: ${verifier.length}")
    assertTrue(Pkce.verifyS256(verifier, Pkce.challengeFor(verifier)))
  }

  @Test
  fun `two generated verifiers differ`() {
    assertFalse(Pkce.generateVerifier() == Pkce.generateVerifier())
  }
}
