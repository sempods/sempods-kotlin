package org.sempods.commons.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HashUtilTest {

  @Test
  fun `sha256Hex of empty string matches the well-known digest`() {
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      HashUtil.sha256Hex(""),
    )
  }

  @Test
  fun `sha256Hex of abc matches the well-known digest`() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      HashUtil.sha256Hex("abc"),
    )
  }

  @Test
  fun `sha256Hex is deterministic for the same input`() {
    val a = HashUtil.sha256Hex("https://sempods.org/alice/contacts/bob")
    val b = HashUtil.sha256Hex("https://sempods.org/alice/contacts/bob")
    assertEquals(a, b)
  }

  @Test
  fun `sha256Hex differs for inputs that vary in any character`() {
    val a = HashUtil.sha256Hex("https://sempods.org/alice/contacts/bob")
    val b = HashUtil.sha256Hex("https://sempods.org/alice/contacts/bob2")
    assertNotEquals(a, b)
  }

  @Test
  fun `sha256Hex of a Base64-URL secret keeps the value sempods-mcp stored under US_ASCII`() {
    // The migration guard for the stores that key rows on this digest: every value they hash is
    // ASCII, so the digest under UTF-8 is the one already in the database. Changing this constant
    // means every in-flight auth code, login state and refresh token stops resolving.
    assertEquals(
      "7bf4c04dc9d14b4f50c5c6ab6d3d6894f42e89b39c2cec8a4c92fc5ec83e15be",
      HashUtil.sha256Hex("K7mQ2xVzR0aB-cD9efGhIjKlMnOpQrStUvWxYz01234"),
    )
  }

  @Test
  fun `sha256Hex handles UTF-8 strings`() {
    val withUmlaut = HashUtil.sha256Hex("Bob Müller")
    val withoutUmlaut = HashUtil.sha256Hex("Bob Mueller")
    assertNotEquals(withUmlaut, withoutUmlaut)
    assertEquals(64, withUmlaut.length)
  }
}
