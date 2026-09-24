package org.sempods.client

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SempodsPkceTest {

  @Test
  fun `the challenge is RFC 7636's own example`() {
    val pkce = SempodsPkce.of("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
    assertEquals("S256", pkce.method)
  }

  @Test
  fun `a generated verifier is 43 unreserved characters, and never the same twice`() {
    val first = SempodsPkce.generate()
    val second = SempodsPkce.generate()

    assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(first.verifier), first.verifier)
    assertNotEquals(first.verifier, second.verifier)
    assertEquals(first.challenge, SempodsPkce.of(first.verifier).challenge)
  }

  @ParameterizedTest
  @ValueSource(strings = ["short", "has space and is long enough to pass the length rule of rfc", "ü-is-not-unreserved-and-this-is-long-enough-yes"])
  fun `a verifier outside RFC 7636's alphabet or length is refused`(verifier: String) {
    assertThrows<IllegalArgumentException> { SempodsPkce.of(verifier) }
  }

  @Test
  fun `the verifier is not printed`() {
    val pkce = SempodsPkce.generate()

    assertFalse(pkce.toString().contains(pkce.verifier), pkce.toString())
  }
}
