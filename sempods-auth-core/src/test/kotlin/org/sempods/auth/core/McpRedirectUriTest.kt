package org.sempods.auth.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpRedirectUriTest {

  @Test fun `https is allowed for any host`() = assertTrue(RedirectUri.isValid("https://app.example.com/cb"))

  @Test fun `http is allowed only on loopback`() {
    assertTrue(RedirectUri.isValid("http://127.0.0.1:51000/cb"))
    assertTrue(RedirectUri.isValid("http://localhost:8080/cb"))
    assertFalse(RedirectUri.isValid("http://evil.example.com/cb"))
  }

  @Test fun `the IPv6 loopback literal is loopback, brackets and all`() {
    // RFC 8252 §7.3 names this alongside `127.0.0.1` as what a native client binds, and
    // `URI.getHost` returns it bracketed.
    assertTrue(RedirectUri.isValid("http://[::1]:51000/cb"))
    assertTrue(RedirectUri.isLoopback(URI("http://[::1]:51000/cb").host))
    assertTrue(RedirectUri.isLoopback("[::1]"))
    assertTrue(RedirectUri.isLoopback("::1"))
  }

  @Test fun `bracketing does not turn a public IPv6 address into loopback`() {
    assertFalse(RedirectUri.isValid("http://[2001:db8::1]/cb"))
    assertFalse(RedirectUri.isLoopback("[2001:db8::1]"))
    assertTrue(RedirectUri.isValid("https://[2001:db8::1]/cb"))
  }

  @Test fun `canonicalize drops the port for the IPv6 loopback literal too`() {
    // An ephemeral port falls away as it does for `127.0.0.1`; the brackets survive the trip.
    assertEquals(
      RedirectUri.canonicalize("http://[::1]:51000/cb"),
      RedirectUri.canonicalize("http://[::1]:65373/cb"),
    )
    assertEquals("http://[::1]/cb", RedirectUri.canonicalize("http://[::1]:51000/cb"))
    assertNotEquals(
      RedirectUri.canonicalize("https://[2001:db8::1]:8443/cb"),
      RedirectUri.canonicalize("https://[2001:db8::1]:9443/cb"),
    )
  }

  @Test fun `canonicalize drops the port and nothing else`() {
    // The `dyn:` match and the registration fingerprint compare these strings verbatim, so an
    // escaped delimiter must not decode: one segment named `cb/admin` is not two segments.
    assertNotEquals(
      RedirectUri.canonicalize("http://localhost:51000/cb%2Fadmin"),
      RedirectUri.canonicalize("http://localhost:65373/cb/admin"),
    )
    assertNotEquals(
      RedirectUri.canonicalize("http://[::1]:51000/cb%2Fadmin"),
      RedirectUri.canonicalize("http://[::1]:65373/cb/admin"),
    )
    assertNotEquals(
      RedirectUri.canonicalize("http://127.0.0.1:51000/cb?a=1%26b=2"),
      RedirectUri.canonicalize("http://127.0.0.1:65373/cb?a=1&b=2"),
    )

    // The escape survives; the port — the one thing meant to go — still goes.
    assertEquals("http://localhost/cb%2Fadmin", RedirectUri.canonicalize("http://localhost:51000/cb%2Fadmin"))
    assertEquals(
      RedirectUri.canonicalize("http://localhost:51000/cb%2Fadmin"),
      RedirectUri.canonicalize("http://localhost:65373/cb%2Fadmin"),
    )
  }

  @Test fun `canonicalize leaves everything that is not a port-insensitive host alone`() {
    for (untouched in listOf(
      "https://app.example.com:8443/cb%2Fadmin",
      "https://app.example.com/cb?a=1%26b=2",
      "https://[2001:db8::1]:8443/cb",
    )) {
      assertEquals(untouched, RedirectUri.canonicalize(untouched))
    }
  }

  @Test fun `canonicalize keeps the parts of an address that are not the port`() {
    // A registered `redirect_uri` may carry a query, and `0.0.0.0` is port-insensitive for
    // matching even though it may not be redirected to.
    assertEquals("http://localhost/cb?next=%2Fhome", RedirectUri.canonicalize("http://localhost:51000/cb?next=%2Fhome"))
    assertEquals("http://0.0.0.0/cb", RedirectUri.canonicalize("http://0.0.0.0:8080/cb"))
    assertEquals("http://localhost/", RedirectUri.canonicalize("http://localhost:51000/"))
    assertEquals("http://localhost", RedirectUri.canonicalize("http://localhost:51000"))
    // Not a URI at all: handed back untouched rather than mangled.
    assertEquals("not a uri", RedirectUri.canonicalize("not a uri"))
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
