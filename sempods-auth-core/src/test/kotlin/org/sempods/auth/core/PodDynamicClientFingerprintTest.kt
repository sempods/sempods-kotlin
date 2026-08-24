package org.sempods.auth.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PodDynamicClientFingerprintTest {

  @Test
  fun `loopback port differences should not change the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "Claude Code",
      userAgent = "claude-code-cli/0.1",
      realm = null,
      redirectUris = setOf("http://localhost:52341/callback", "http://127.0.0.1:52341/retry"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "Claude Code",
      userAgent = "claude-code-cli/0.1",
      realm = null,
      redirectUris = setOf("http://localhost:7123/callback", "http://127.0.0.1:7123/retry"),
    )
    assertEquals(a, b)
  }

  @Test
  fun `clientName difference should change the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "Claude Code",
      userAgent = "ua/1",
      realm = null,
      redirectUris = setOf("http://localhost/cb"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "Claude Desktop",
      userAgent = "ua/1",
      realm = null,
      redirectUris = setOf("http://localhost/cb"),
    )
    assertNotEquals(a, b)
  }

  @Test
  fun `userAgent difference should change the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua/1",
      realm = null,
      redirectUris = setOf("http://localhost/cb"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua/2",
      realm = null,
      redirectUris = setOf("http://localhost/cb"),
    )
    assertNotEquals(a, b)
  }

  @Test
  fun `non-loopback host port should be preserved in the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = null,
      redirectUris = setOf("https://apps.example.org:8443/cb"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = null,
      redirectUris = setOf("https://apps.example.org:9443/cb"),
    )
    assertNotEquals(a, b)
  }

  @Test
  fun `redirect URI ordering should not change the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = null,
      redirectUris = linkedSetOf("http://localhost:1/a", "http://localhost:2/b"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = null,
      redirectUris = linkedSetOf("http://localhost:2/b", "http://localhost:1/a"),
    )
    assertEquals(a, b)
  }

  @Test
  fun `realm difference should change the fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "ChatGPT",
      userAgent = "chatgpt/1",
      realm = "default",
      redirectUris = setOf("https://chatgpt.com/connector/oauth/ABC"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "ChatGPT",
      userAgent = "chatgpt/1",
      realm = "default/chatgpt-work",
      redirectUris = setOf("https://chatgpt.com/connector/oauth/ABC"),
    )
    assertNotEquals(
      a, b,
      "distinct realms must fork fingerprints — this is the ChatGPT-collapse fix",
    )
  }

  @Test
  fun `null and blank realm should produce the same fingerprint`() {
    val a = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = null,
      redirectUris = setOf("http://localhost/cb"),
    )
    val b = DynamicClientFingerprint.compute(
      clientName = "X",
      userAgent = "ua",
      realm = "",
      redirectUris = setOf("http://localhost/cb"),
    )
    assertEquals(a, b, "null and empty realms are treated identically")
  }
}
