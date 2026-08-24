package org.sempods.commons.net

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearerAuthTest {

  @Test
  fun `parse extracts the token`() {
    assertEquals("sc_abc", BearerAuth.parse("Bearer sc_abc"))
  }

  @Test
  fun `the scheme name is case-insensitive`() {
    // RFC 7235 §2.1 — a gateway or client normalising the scheme must not cost a valid credential.
    assertEquals("sc_abc", BearerAuth.parse("bearer sc_abc"))
    assertEquals("sc_abc", BearerAuth.parse("BEARER sc_abc"))
    assertEquals("sc_abc", BearerAuth.parse("BeArEr sc_abc"))
  }

  @Test
  fun `surrounding and separating whitespace is tolerated`() {
    assertEquals("sc_abc", BearerAuth.parse("  Bearer   sc_abc  "))
  }

  @Test
  fun `the token itself keeps its case and characters`() {
    // It is compared byte for byte against a stored value — no normalisation may happen here.
    assertEquals("sC_AbC-+/=", BearerAuth.parse("Bearer sC_AbC-+/="))
  }

  @Test
  fun `anything unusable is null`() {
    assertNull(BearerAuth.parse(null))
    assertNull(BearerAuth.parse(""))
    assertNull(BearerAuth.parse("   "))
    assertNull(BearerAuth.parse("Bearer"))
    assertNull(BearerAuth.parse("Bearer "))
    // a different scheme is not ours to interpret
    assertNull(BearerAuth.parse("Basic sc_abc"))
    assertNull(BearerAuth.parse("sc_abc"))
    // no separator — `Bearerabc` is not a Bearer header
    assertNull(BearerAuth.parse("Bearerabc"))
  }
}
