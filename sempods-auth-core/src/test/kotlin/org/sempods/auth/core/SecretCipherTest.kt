package org.sempods.auth.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretCipherTest {

  private val cipher = SecretCipher(ByteArray(32) { (it + 1).toByte() })

  @Test
  fun `reads a value the cipher wrote before it moved out of sempods-mcp`() {
    // Written by `org.sempods.mcp.crypto.SecretCipher` under this test's key. Rows in a deployed
    // vault were written by that class, and moving it must not make one of them unreadable.
    val writtenBeforeTheMove = "v1:kAnZ8I5-jjew3PyDmNg0uCKHilivhIfGRv7PHDgN7JTcfxtrfrpaa2--wU4skDNgtJGsogBb"

    assertEquals("written-by-sempods-mcp-0.2", cipher.decrypt(writtenBeforeTheMove))
  }

  @Test
  fun `round-trips plaintext`() {
    val secret = "pod-access-token-abc.123-ÄÖÜ"
    assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
  }

  @Test
  fun `ciphertext carries the version prefix and is not the plaintext`() {
    val out = cipher.encrypt("hello")
    assertTrue(out.startsWith("v1:"), "expected version prefix, got: $out")
    assertTrue(!out.contains("hello"))
  }

  @Test
  fun `same plaintext encrypts to different ciphertext each time (random IV)`() {
    assertTrue(cipher.encrypt("x") != cipher.encrypt("x"))
  }

  @Test
  fun `decrypt rejects an unknown format`() {
    assertFailsWith<IllegalArgumentException> { cipher.decrypt("not-encrypted") }
  }

  @Test
  fun `decrypt fails on tampered ciphertext (GCM tag)`() {
    val out = cipher.encrypt("payload")
    // Tamper a *byte* of the encrypted payload (IV‖ciphertext‖tag), then re-encode. Flipping the last
    // base64 char (the old approach) was ~6%-flaky: the ciphertext length is not a multiple of 3, so
    // the final base64 char carries unused low bits, and flipping 'A'↔'B' there decodes to identical
    // bytes → the GCM tag still verified. Flipping a byte of the tag always fails authentication.
    val raw = Base64.getUrlDecoder().decode(out.removePrefix("v1:"))
    raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
    val tampered = "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    assertFailsWith<IllegalArgumentException> { cipher.decrypt(tampered) }
  }

  @Test
  fun `decrypt fails under a different key`() {
    val out = cipher.encrypt("payload")
    val other = SecretCipher(ByteArray(32) { (it + 2).toByte() })
    assertFailsWith<IllegalArgumentException> { other.decrypt(out) }
  }

  @Test
  fun `a key is read from base64 and from base64url`() {
    val key = ByteArray(32) { (it + 1).toByte() }
    for (encoded in listOf(Base64.getEncoder().encodeToString(key), Base64.getUrlEncoder().encodeToString(key))) {
      assertEquals("x", SecretCipher.fromBase64(encoded).decrypt(cipher.encrypt("x")))
    }
    assertFailsWith<IllegalArgumentException> { SecretCipher.fromBase64(Base64.getEncoder().encodeToString(ByteArray(16))) }
  }

  @Test
  fun `constructor rejects a wrong-length key`() {
    assertFailsWith<IllegalArgumentException> { SecretCipher(ByteArray(16)) }
  }

  @Test
  fun `encryptMaybe and decryptMaybe pass null through`() {
    assertNull(cipher.encryptMaybe(null))
    assertNull(cipher.decryptMaybe(null))
  }

  @Test
  fun `decryptMaybe round-trips a non-null value`() {
    assertEquals("secret", cipher.decryptMaybe(cipher.encryptMaybe("secret")))
  }
}
