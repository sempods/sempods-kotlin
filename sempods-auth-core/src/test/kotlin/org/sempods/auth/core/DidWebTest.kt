package org.sempods.auth.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DidWebTest {

  @Test
  fun `a default-port https base yields a bare-host did`() {
    assertEquals("did:web:mcp.sempods.org", DidWeb.clientId("https://mcp.sempods.org"))
    assertEquals("did:web:mcp.sempods.org", DidWeb.clientId("https://mcp.sempods.org/"))
    assertEquals("did:web:mcp.sempods.org", DidWeb.clientId("https://mcp.sempods.org:443"))
  }

  @Test
  fun `a non-default port is percent-encoded into the did (W3C did-web)`() {
    assertEquals("did:web:localhost%3A8080", DidWeb.clientId("http://localhost:8080"))
    assertEquals("did:web:mcp.example%3A8443", DidWeb.clientId("https://mcp.example:8443"))
  }

  @Test
  fun `a path prefix becomes colon-separated segments, and comes back as the subtree it covers`() {
    // How one service holds more than one identity: the hosted MCP service gives each named
    // profile its own, so the two arrive at a pod as different clients with different grants.
    val clientId = DidWeb.clientId("https://mcp.sempods.org", listOf("cron-agent"))
    assertEquals("did:web:mcp.sempods.org:cron-agent", clientId)

    val target = assertNotNull(DidWeb.targetOf(clientId))
    assertEquals("/cron-agent", target.pathPrefix)
    assertTrue(target.covers(URI("https://mcp.sempods.org/cron-agent/_system/ui/pods/callback")))
    assertFalse(
      target.covers(URI("https://mcp.sempods.org/_system/ui/pods/callback")),
      "the default profile's callback is outside a named profile's identity — that is the whole point",
    )
    assertFalse(target.covers(URI("https://mcp.sempods.org/cron-agent-other/cb")))

    assertEquals(
      "did:web:localhost%3A8080:cron-agent",
      DidWeb.clientId("http://localhost:8080", listOf("cron-agent")),
      "the port escape and the path segments are independent",
    )
  }

  @Test
  fun `a segment outside the DID character set is refused`() {
    // Two reasons, both of which end in an identifier that cannot be used. `a/b`, `a:b` and `a%2Fb`
    // do not parse back to themselves — `targetOf` splits on ':' and percent-decodes — so the
    // identifier would cover a subtree nobody named. `café` and a newline are not a DID at all, and
    // `ClientId.isValid` (RFC 6749 `*VSCHAR`) refuses them further down, so minting one would hand
    // a caller an identity that fails at `/authorize` instead of here.
    listOf("a/b", "a:b", "a%2Fb", " ", "", "café", "line\nbreak", "a b").forEach { segment ->
      assertFailsWith<IllegalArgumentException>("segment '$segment'") {
        DidWeb.clientId("https://mcp.sempods.org", listOf(segment))
      }
    }

    // What the grammar does allow, minted and parsed back.
    val clientId = DidWeb.clientId("https://mcp.sempods.org", listOf("a-b_c.d", "9"))
    assertEquals("did:web:mcp.sempods.org:a-b_c.d:9", clientId)
    assertTrue(ClientId.isValid(clientId))
    assertEquals("/a-b_c.d/9", assertNotNull(DidWeb.targetOf(clientId)).pathPrefix)
  }

  @Test
  fun `the did document carries exactly the client id the pod checks`() {
    val clientId = "did:web:mcp.sempods.org"
    assertEquals(clientId, DidWeb.document(clientId)["id"])
  }
}
