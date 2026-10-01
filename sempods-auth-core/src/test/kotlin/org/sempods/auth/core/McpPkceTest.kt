package org.sempods.auth.core

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
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
    // Both verifiers are well formed, so this fails on the comparison and not on the length rule
    // below — which is what it is here to test.
    val real = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    val other = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstwabc"
    assertEquals(43, real.length)
    assertEquals(43, other.length)
    assertFalse(Pkce.verifyS256(other, challengeFor(real)))
  }

  @Test
  fun `a verifier RFC 7636 does not permit cannot match anything`() {
    // 43 characters is what makes a verifier too expensive to guess for an attacker holding an
    // intercepted code and the challenge that came with it. A shorter one hashes to a challenge
    // just as well, so nothing but this rule stops it.
    for (illegal in listOf(
      "a".repeat(42),
      "a".repeat(129),
      "a".repeat(42) + "ä",
      // `+` and `/` are base64, not the unreserved set §4.1 names.
      "a".repeat(41) + "+/",
      "",
    )) {
      assertFalse(Pkce.isLegalVerifier(illegal), "should be illegal: ${illegal.length} chars")
      assertFalse(
        Pkce.verifyS256(illegal, challengeFor(illegal)),
        "an illegal verifier must not verify, not even against its own challenge",
      )
    }
  }

  @Test
  fun `the legal range is exactly the one the specification names`() {
    assertTrue(Pkce.isLegalVerifier("a".repeat(43)))
    assertTrue(Pkce.isLegalVerifier("a".repeat(128)))
    // The full unreserved set, which is wider than base64url.
    assertTrue(Pkce.isLegalVerifier("-._~" + "a".repeat(39)))
  }

  @Test
  fun `an S256 challenge is 43 base64url characters and nothing else`() {
    assertTrue(Pkce.isLegalS256Challenge(challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))
    assertTrue(Pkce.isLegalS256Challenge("-_" + "a".repeat(40) + "w"))
    // The last character holds four digest bits and two zero bits: a challenge any digest produces.
    repeat(200) { assertTrue(Pkce.isLegalS256Challenge(Pkce.challengeFor(Pkce.generateVerifier()))) }
    for (illegal in listOf(
      // 43 base64url characters, but the last one sets bits a 32-byte digest does not have.
      "a".repeat(43),
      "a".repeat(42) + "B",
      "short",
      "",
      "a".repeat(42),
      "a".repeat(44),
      // A legal `plain` challenge under RFC 7636 §4.2, and no S256 one.
      "a".repeat(128),
      // `.` and `~` are unreserved but not base64url; `=` is padding, `+` and `/` standard base64.
      "~" + "a".repeat(41) + "w",
      "." + "a".repeat(41) + "w",
      "a".repeat(42) + "=",
      "+/" + "a".repeat(40) + "w",
    )) {
      assertFalse(Pkce.isLegalS256Challenge(illegal), "should be illegal: '$illegal'")
    }
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
