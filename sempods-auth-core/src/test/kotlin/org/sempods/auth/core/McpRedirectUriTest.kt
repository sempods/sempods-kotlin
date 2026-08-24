package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRedirectUriTest {

  @Test fun `https is allowed for any host`() = assertTrue(RedirectUri.isValid("https://app.example.com/cb"))

  @Test fun `http is allowed only on loopback`() {
    assertTrue(RedirectUri.isValid("http://127.0.0.1:51000/cb"))
    assertTrue(RedirectUri.isValid("http://localhost:8080/cb"))
    assertFalse(RedirectUri.isValid("http://evil.example.com/cb"))
  }

  @Test fun `fragments are rejected`() {
    assertFalse(RedirectUri.isValid("https://app.example.com/cb#frag"))
    assertFalse(RedirectUri.isValid("http://127.0.0.1/cb#x"))
  }

  @Test fun `non-http(s) schemes are rejected for now`() {
    assertFalse(RedirectUri.isValid("ftp://example.com/cb"))
    assertFalse(RedirectUri.isValid("com.example.app:/cb"))
  }

  @Test fun `malformed or relative URIs are rejected`() {
    assertFalse(RedirectUri.isValid("not a uri"))
    assertFalse(RedirectUri.isValid("/relative/cb"))
    assertFalse(RedirectUri.isValid("https://"))
  }
}
