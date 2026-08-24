package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class McpDynamicClientFingerprintTest {

  @Test
  fun `loopback redirect URIs differing only in port produce the same fingerprint`() {
    val a = DynamicClientFingerprint.compute("Claude", "claude/1.0", "default", setOf("http://127.0.0.1:51000/cb"))
    val b = DynamicClientFingerprint.compute("Claude", "claude/1.0", "default", setOf("http://127.0.0.1:62000/cb"))
    assertEquals(a, b, "ephemeral loopback port must not change the fingerprint")
  }

  @Test
  fun `different profiles fork into different fingerprints`() {
    val uris = setOf("http://127.0.0.1:51000/cb")
    val default = DynamicClientFingerprint.compute("Claude", "claude/1.0", "default", uris)
    val sandbox = DynamicClientFingerprint.compute("Claude", "claude/1.0", "sandbox", uris)
    assertNotEquals(default, sandbox)
  }

  @Test
  fun `non-loopback redirect URIs keep their port`() {
    assertEquals(
      "https://app.example.com:8443/cb",
      RedirectUri.canonicalize("https://app.example.com:8443/cb"),
    )
  }
}
