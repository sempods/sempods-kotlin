package org.sempods.auth.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
  fun `the did document carries exactly the client id the pod checks`() {
    val clientId = "did:web:mcp.sempods.org"
    assertEquals(clientId, DidWeb.document(clientId)["id"])
  }
}
