package org.sempods.auth.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who may be handed a response, and — the half that is easy to get wrong — who may be handed an
 * *error* response.
 *
 * Every case here is silent when it fails: a redirect to the wrong origin looks like a redirect,
 * and the damage is that a token or a laundered link arrives somewhere it should not.
 */
class ClientRedirectPolicyTest {

  private val policy = DidWebRedirectPolicy()
  private val devPolicy = DidWebRedirectPolicy(allowLoopback = true)

  @Test
  fun `a client may be answered on its own origin`() {
    assertTrue(policy.permits("did:web:pod.example.org", "https://pod.example.org/callback"))
    assertTrue(policy.permits("did:web:pod.example.org", "https://pod.example.org/a/b?x=1"))
  }

  @Test
  fun `and nowhere else`() {
    // The attack the whole mechanism exists for: a real client id, someone else's address.
    assertFalse(policy.permits("did:web:pod.example.org", "https://evil.example/grab"))
    assertFalse(policy.permits("did:web:pod.example.org", "https://pod.example.org.evil.test/grab"))
    assertFalse(policy.permits("did:web:pod.example.org", "https://sub.pod.example.org/grab"))
  }

  @Test
  fun `the port is part of the origin`() {
    assertTrue(policy.permits("did:web:pod.example.org", "https://pod.example.org:443/cb"))
    assertFalse(policy.permits("did:web:pod.example.org", "https://pod.example.org:8443/cb"))
    assertTrue(policy.permits("did:web:pod.example.org%3A8443", "https://pod.example.org:8443/cb"))
  }

  @Test
  fun `a path-scoped identifier is confined to its subtree`() {
    // Two services on one host must not be able to answer for each other.
    val scoped = "did:web:example.org:mcp"
    assertTrue(policy.permits(scoped, "https://example.org/mcp"))
    assertTrue(policy.permits(scoped, "https://example.org/mcp/callback"))
    assertFalse(policy.permits(scoped, "https://example.org/other/callback"))
    assertFalse(policy.permits(scoped, "https://example.org/callback"))
  }

  @Test
  fun `a sibling that merely shares the first characters is not inside the subtree`() {
    // The bug a string prefix produces: `/mcp-other` starts with `/mcp` and is a different
    // service. `authorization.md` states the same rule for the `manage` scope and says why it is
    // load-bearing — a prefix describes a tree, and `-` is not a separator.
    val scoped = "did:web:example.org:mcp"

    assertFalse(policy.permits(scoped, "https://example.org/mcp-other/callback"))
    assertFalse(policy.permits(scoped, "https://example.org/mcpother"))
  }

  @Test
  fun `a host-root identifier covers the whole host`() {
    assertTrue(policy.permits("did:web:example.org", "https://example.org/anything/at/all"))
  }

  @Test
  fun `we do not mint what we cannot serve`() {
    // `clientId` refuses a base URL with a path, because the DID document would then have to live
    // under that prefix and this stack serves it at the root. Parsing stays permissive on purpose
    // — a third party may legitimately present a path-scoped identifier, and the validating side
    // has to understand what it is being shown.
    assertEquals("did:web:example.org", DidWeb.clientId("https://example.org"))
    assertEquals("did:web:example.org", DidWeb.clientId("https://example.org/"))
    assertFailsWith<IllegalArgumentException> { DidWeb.clientId("https://example.org/mcp") }

    assertNotNull(DidWeb.targetOf("did:web:example.org:mcp"))
  }

  @Test
  fun `plain http is refused except on loopback, and loopback only in development`() {
    assertFalse(policy.permits("did:web:pod.example.org", "http://pod.example.org/cb"))
    assertFalse(policy.permits("did:web:pod.example.org", "http://localhost:1234/cb"))
    assertTrue(devPolicy.permits("did:web:pod.example.org", "http://localhost:1234/cb"))
  }

  @Test
  fun `a fragment can never be registered`() {
    // RFC 6749 §3.1.2 — the component is reserved for the response.
    assertFalse(policy.permits("did:web:pod.example.org", "https://pod.example.org/cb#frag"))
  }

  @Test
  fun `an unknown client identifier permits nothing`() {
    assertFalse(policy.permits("dyn:abc", "https://pod.example.org/cb"))
    assertFalse(policy.permits("", "https://pod.example.org/cb"))
    assertFalse(policy.permits("https://pod.example.org", "https://pod.example.org/cb"))
  }

  // ─── the error path ───────────────────────────────────────────────────────

  @Test
  fun `an error may only be redirected once the address is proven`() {
    // The pod server reported an unknown client_id by redirecting to the unvalidated redirect_uri,
    // before the check that would have rejected it — an open redirector on the AS origin. There is
    // no target here to redirect to, so that shape cannot be built.
    assertNull(OAuthErrors.redirectTargetFor(policy, "did:web:pod.example.org", "https://evil.example/grab"))
    assertNull(OAuthErrors.redirectTargetFor(policy, "garbage", "https://evil.example/grab"))
    assertNull(OAuthErrors.redirectTargetFor(policy, null, "https://pod.example.org/cb"))
    assertNull(OAuthErrors.redirectTargetFor(policy, "did:web:pod.example.org", null))

    assertNotNull(OAuthErrors.redirectTargetFor(policy, "did:web:pod.example.org", "https://pod.example.org/cb"))
  }

  @Test
  fun `the error page pointer names the code`() {
    val uri = OAuthErrors.errorUri("https://sempods.org/docs/oauth-errors", OAuthErrorCode.INVALID_REQUEST)

    kotlin.test.assertEquals("https://sempods.org/docs/oauth-errors#invalid_request", uri)
  }
}
