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
    // `URI.getHost` returns `[::1]` — with the brackets — so a comparison against a set that
    // spells the address `::1` used to miss. RFC 8252 §7.3 names this address alongside
    // `127.0.0.1` as what a native client binds, so missing it refused the exact callback the
    // loopback carve-out exists for.
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
    // The `dyn:` match compares canonical forms, so an ephemeral port has to fall away here for
    // the same reason it does for `127.0.0.1` — and the brackets have to survive the round trip.
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
    // It used to run the address through the multi-argument `URI` constructor, which takes decoded
    // parts — so `/cb%2Fadmin` came back as `/cb/admin`. One path segment literally named
    // `cb/admin` and two segments `cb` then `admin` became the same string, and the `dyn:` match
    // compares exactly these strings: an address that was never registered compared equal to one
    // that was. An encoded `&` in a query collapsed the same way.
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

    // The escape survives verbatim, and the port — the one thing that is meant to go — still goes.
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
    // A registered `redirect_uri` may carry a query of its own, and `0.0.0.0` is port-insensitive
    // for matching even though it may not be redirected to.
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
