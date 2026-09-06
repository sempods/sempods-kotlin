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
  fun `a segment that would not survive the round trip is refused`() {
    // `targetOf` splits on ':' and percent-decodes each segment, so these three would come back as
    // something other than what was minted — and an identifier that does not parse to itself
    // covers a subtree nobody named.
    listOf("a/b", "a:b", "a%2Fb", " ").forEach { segment ->
      assertFailsWith<IllegalArgumentException>("segment '$segment'") {
        DidWeb.clientId("https://mcp.sempods.org", listOf(segment))
      }
    }
  }

  @Test
  fun `the did document carries exactly the client id the pod checks`() {
    val clientId = "did:web:mcp.sempods.org"
    assertEquals(clientId, DidWeb.document(clientId)["id"])
  }
}
