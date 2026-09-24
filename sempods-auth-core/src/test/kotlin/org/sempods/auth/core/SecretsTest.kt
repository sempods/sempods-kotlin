package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretsTest {

  @Test
  fun `what it mints, it recognises`() {
    repeat(20) { assertTrue(Secrets.isWellFormed(Secrets.newSecret())) }
  }

  @Test
  fun `an opaque id is URL-safe and never repeats`() {
    val ids = List(100) { Secrets.newOpaqueId() }
    assertEquals(ids.size, ids.toSet().size, "two mints collided")
    for (id in ids) {
      assertEquals(24, id.length, "18 bytes base64url, unpadded: '$id'")
      assertTrue(id.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }, id)
    }
  }

  @Test
  fun `an opaque id does not pass for a secret`() {
    // What keeps the two mints from quietly becoming one: `isWellFormed` answers for `newSecret`.
    assertFalse(Secrets.isWellFormed(Secrets.newOpaqueId()))
  }

  @Test
  fun `anything that could break a grammar it is put into is refused`() {
    // The point of the check: these are the values that make a cookie name unbuildable.
    for (bad in listOf(null, "", " ", "a;b", "a=b", "a,b", "a\tb", "ä", "x".repeat(44), "x".repeat(42))) {
      assertFalse(Secrets.isWellFormed(bad), "should be refused: '$bad'")
    }
  }

  @Test
  fun `a shaped value is not a valid one`() {
    // Shape says where a value may be *put*, never whether it is real — that is the store's answer.
    assertTrue(Secrets.isWellFormed("A".repeat(43)))
  }
}
