package org.sempods.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where the browser is told to send these back.
 *
 * A cookie path is not cosmetic: get it wrong and the browser silently withholds the value, the
 * callback reports a missing pin, and nothing in that message points at the path. Both cases below
 * came out of review rather than out of a failure, which is the argument for pinning them.
 */
class PodBrowserCookiesTest {

  private val state = "a-flow-state"

  @Test
  fun `a deployment mounted under a path prefix gets cookies under that prefix`() {
    // Every pod route then lives below the prefix, so a cookie rooted at `/{pod}/` is never sent
    // back and every interactive login fails on a pin the browser was told not to send.
    val cookies = PodBrowserCookies("https://example.test/sempods/", secure = true)

    assertEquals("/sempods/alice/", cookies.session("alice", "t", 60).path)
    assertEquals("/sempods/alice/_system/auth/oidc/callback", cookies.loginPin("alice", state, "p", 60).path)
  }

  @Test
  fun `an encoded prefix stays encoded, because that is what the browser matches`() {
    // A cookie's `Path` is compared against the raw request path (RFC 6265 §5.1.4). Decoding it
    // would emit `Path=/sempods prod/…` against requests for `/sempods%20prod/…` — no match, both
    // cookies withheld, and every interactive login failing on a pin the browser never sent.
    val cookies = PodBrowserCookies("https://example.test/sempods%20prod/", secure = true)

    assertEquals("/sempods%20prod/alice/", cookies.session("alice", "t", 60).path)
    assertEquals(
      "/sempods%20prod/alice/_system/auth/oidc/callback",
      cookies.loginPin("alice", state, "p", 60).path,
    )
  }

  @Test
  fun `a host-root deployment gets no stray prefix`() {
    val cookies = PodBrowserCookies("https://sempods.org/", secure = true)

    assertEquals("/alice/", cookies.session("alice", "t", 60).path)
    assertEquals("/alice/_system/auth/oidc/callback", cookies.loginPin("alice", state, "p", 60).path)
  }

  @Test
  fun `two flows in one browser do not share a pin cookie`() {
    // With one fixed name the second redirect overwrites the first pin; the first callback then
    // fails *and clears the shared cookie*, so the second finds nothing either — one concurrent
    // login breaks both.
    val cookies = PodBrowserCookies("https://sempods.org/", secure = true)

    val first = cookies.loginPin("alice", "state-one", "pin-one", 60)
    val second = cookies.loginPin("alice", "state-two", "pin-two", 60)

    assertTrue(first.name != second.name, "each flow needs its own cookie: ${first.name}")
    assertEquals(first.path, second.path, "…while still being scoped to the same callback")
  }

  @Test
  fun `withdrawing a pin names the same cookie it set`() {
    // A clear with a different name withdraws nothing and leaves the pin usable.
    val cookies = PodBrowserCookies("https://sempods.org/", secure = true)

    val set = cookies.loginPin("alice", state, "pin", 60)
    val cleared = cookies.clearLoginPin("alice", state)

    assertEquals(set.name, cleared.name)
    assertEquals(set.path, cleared.path)
    assertEquals(0, cleared.maxAge)
  }

  @Test
  fun `a pod's cookies never reach another pod`() {
    val cookies = PodBrowserCookies("https://sempods.org/", secure = true)

    assertEquals("/alice/", cookies.session("alice", "t", 60).path)
    assertEquals("/bob/", cookies.session("bob", "t", 60).path)
  }

  @Test
  fun `http development does not mark cookies secure, https does`() {
    assertTrue(!PodBrowserCookies("http://localhost:8090/", secure = false).session("a", "t", 60).isSecure)
    assertTrue(PodBrowserCookies("https://sempods.org/", secure = true).session("a", "t", 60).isSecure)
  }
}
