package org.sempods.mcp.api.oauth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.sempods.mcp.SempodsMcpConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class OAuthMetadataEndpointTest {

  private val config = SempodsMcpConfig(
    port = 0, mongoUrl = "", mongoDbName = "",
    mcpBaseUrl = "https://mcp.test", authIssuers = listOf("https://id.test"),
  )
  private val mapper = jacksonObjectMapper()

  @Test
  fun `protected-resource metadata points at the service as resource and AS`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    val resp = client.get("/.well-known/oauth-protected-resource")
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = mapper.readTree(resp.bodyAsText())
    assertEquals("https://mcp.test", body["resource"].asText())
    assertEquals("https://mcp.test", body["authorization_servers"][0].asText())
  }

  @Test
  fun `authorization-server metadata advertises the AS endpoints and S256`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    val body = mapper.readTree(client.get("/.well-known/oauth-authorization-server").bodyAsText())
    assertEquals("https://mcp.test", body["issuer"].asText())
    assertEquals("https://mcp.test/authorize", body["authorization_endpoint"].asText())
    assertEquals("https://mcp.test/token", body["token_endpoint"].asText())
    assertEquals("https://mcp.test/register", body["registration_endpoint"].asText())
    assertEquals("S256", body["code_challenge_methods_supported"][0].asText())
  }

  @Test
  fun `did-web document identifies the service so a static-client pod can resolve it`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    val resp = client.get("/.well-known/did.json")
    assertEquals(HttpStatusCode.OK, resp.status)
    // A did:web pod resolves our client_id (did:web:<host>) to this document and accepts the connect
    // only if `id` matches — so it must equal did:web:mcp.test for base https://mcp.test.
    assertEquals("did:web:mcp.test", mapper.readTree(resp.bodyAsText())["id"].asText())
  }

  @Test
  fun `named-profile host-rooted discovery (RFC 9728 path-insertion) is profile-scoped`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    val prm = mapper.readTree(client.get("/.well-known/oauth-protected-resource/private").bodyAsText())
    assertEquals("https://mcp.test/private", prm["resource"].asText())
    assertEquals("https://mcp.test/private", prm["authorization_servers"][0].asText())
    val asm = mapper.readTree(client.get("/.well-known/oauth-authorization-server/private").bodyAsText())
    assertEquals("https://mcp.test/private", asm["issuer"].asText())
    assertEquals("https://mcp.test/private/authorize", asm["authorization_endpoint"].asText())
    assertEquals("https://mcp.test/private/token", asm["token_endpoint"].asText())
    // The signing keys are service-wide, so the JWKS stays at the root.
    assertEquals("https://mcp.test/jwks.json", asm["jwks_uri"].asText())
  }

  @Test
  fun `named-profile append-style discovery is profile-scoped`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    val prm = mapper.readTree(client.get("/private/.well-known/oauth-protected-resource").bodyAsText())
    assertEquals("https://mcp.test/private", prm["resource"].asText())
    val asm = mapper.readTree(client.get("/private/.well-known/oauth-authorization-server").bodyAsText())
    assertEquals("https://mcp.test/private/token", asm["token_endpoint"].asText())
  }

  @Test
  fun `reserved profile segment is not discoverable (no resurrected mcp path)`() = testApplication {
    application { oauthMetadataEndpoint(config, mapper) }
    assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/oauth-protected-resource/mcp").status)
    assertEquals(HttpStatusCode.NotFound, client.get("/mcp/.well-known/oauth-protected-resource").status)
    // The default sentinel is reserved: `/default` is not a routable duplicate of the root.
    assertEquals(HttpStatusCode.NotFound, client.get("/.well-known/oauth-protected-resource/default").status)
    assertEquals(HttpStatusCode.NotFound, client.get("/default/.well-known/oauth-protected-resource").status)
  }
}
