package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientMetadataUriTest {

  @Test
  fun `a scheme that executes rather than fetches is refused`() {
    // The reason the check exists: registration is self-service, `logo_uri` is meant to be
    // rendered, and `javascript:` is a perfectly valid URI.
    assertFalse(ClientMetadataUri.isValid("javascript:alert(1)"))
    assertFalse(ClientMetadataUri.isValid("JavaScript:alert(1)"))
    assertFalse(ClientMetadataUri.isValid("data:text/html,<script>alert(1)</script>"))
    assertFalse(ClientMetadataUri.isValid("vbscript:msgbox(1)"))
    assertFalse(ClientMetadataUri.isValid("file:///etc/passwd"))
  }

  @Test
  fun `https is fine anywhere, and a query or fragment is not this check's business`() {
    // Unlike a redirect target: a logo behind a cache-busting query is an ordinary URL, and
    // refusing it would be a rule this project invented.
    assertTrue(ClientMetadataUri.isValid("https://app.example/logo.png"))
    assertTrue(ClientMetadataUri.isValid("https://app.example/logo.png?v=2"))
    assertTrue(ClientMetadataUri.isValid("https://app.example/logo.png#icon"))
    assertTrue(ClientMetadataUri.isValid("https://app.example/cb?code=x"))
  }

  @Test
  fun `cleartext only where it cannot leave the machine`() {
    assertTrue(ClientMetadataUri.isValid("http://localhost:5173/logo.png"))
    assertTrue(ClientMetadataUri.isValid("http://127.0.0.1:5173/logo.png"))
    assertTrue(ClientMetadataUri.isValid("http://[::1]:5173/logo.png"))
    assertFalse(ClientMetadataUri.isValid("http://app.example/logo.png"))
    // Bind-all is somewhere a server listens, not somewhere a browser is sent.
    assertFalse(ClientMetadataUri.isValid("http://0.0.0.0:5173/logo.png"))
  }

  @Test
  fun `anything that is not an absolute address is refused`() {
    assertFalse(ClientMetadataUri.isValid("/logo.png"))
    assertFalse(ClientMetadataUri.isValid("logo.png"))
    assertFalse(ClientMetadataUri.isValid("not a uri"))
    assertFalse(ClientMetadataUri.isValid(""))
    assertFalse(ClientMetadataUri.isValid("https://"))
  }
}
