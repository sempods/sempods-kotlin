package org.sempods.mcp.api.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.core.ContentItem
import org.sempods.mcp.core.ToolCallResult
import org.sempods.mcp.auth.JwtTestSupport
import org.sempods.auth.core.JwtVerifier
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.core.ReauthorizeChallengeStore
import io.mockk.mockk
import io.mockk.verify
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.concurrent.TimeUnit
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mongo-backed since M6.3 (`ReauthorizeChallengeStore` is a Mongo store); skipped when
 * Mongo is unreachable so the build stays green where it is absent.
 */
class McpEndpointTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping MCP endpoint test")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop(); mongoClient?.close()
    }

    private fun mongoReachable(): Boolean = runCatching {
      val settings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString(MONGO_URL))
        .applyToClusterSettings { it.serverSelectionTimeout(1, TimeUnit.SECONDS) }
        .build()
      MongoClients.create(settings).use { it.getDatabase("admin").runCommand(Document("ping", 1)) }
      true
    }.getOrDefault(false)
  }

  private val base = "https://mcp.test"
  private val config = SempodsMcpConfig(
    port = 0, mongoUrl = "", mongoDbName = "",
    mcpBaseUrl = base, authIssuers = listOf("https://id.test"),
  )
  private val mapper = jacksonObjectMapper()
  private val key = JwtTestSupport.generateKey("svc-1")
  private val verifier = ServiceBearerVerifier(base, JwtVerifier.localKeys(listOf(key.toPublicJWK())))

  private fun accessToken(
    user: String = "https://id.test/e/abc",
    jti: String = UUID.randomUUID().toString(),
    issuedAt: Instant = Instant.now(),
    profile: String = "default",
  ): String {
    val issuer = if (profile == "default") base else "$base/$profile"
    return JwtTestSupport.sign(
      key,
      JWTClaimsSet.Builder()
        .issuer(issuer).subject(user)
        .claim("client_id", "dyn:abc").claim("profile", profile).claim("scope", "")
        .issueTime(Date.from(issuedAt)).expirationTime(Date.from(issuedAt.plusSeconds(3600)))
        .jwtID(jti).build(),
    )
  }

  // The read/write dispatch is exercised by the integration tests; here stubs echo the tool name so
  // we can assert dispatch is reached only for an authenticated call and routed by tool name.
  private val readToolDispatch: suspend (String, com.fasterxml.jackson.databind.JsonNode?, ServiceBearerVerifier.Session) -> ToolCallResult =
    { name, _, _ -> ToolCallResult(content = listOf(ContentItem(type = "text", text = "read:$name"))) }
  private val writeToolDispatch: suspend (String, com.fasterxml.jackson.databind.JsonNode?, ServiceBearerVerifier.Session) -> ToolCallResult =
    { name, _, _ -> ToolCallResult(content = listOf(ContentItem(type = "text", text = "write:$name"))) }

  private val auditLog = mockk<AuditLog>(relaxed = true)

  private fun ApplicationTestBuilder.install(userRateLimitPerMinute: Int = 0) =
    application {
      // Mirror production wiring (SempodsMcpMain) so trailing-slash behaviour is under test.
      install(IgnoreTrailingSlash)
      mcpEndpoint(
        config, verifier, ReauthorizeChallengeStore(db!!, SempodsMcpCollections.OAUTH_REAUTH_CHALLENGES), readToolDispatch, writeToolDispatch, mapper,
        userRateLimiter = UserRateLimiter(userRateLimitPerMinute), auditLog = auditLog,
      )
    }

  private suspend fun ApplicationTestBuilder.rpc(body: String, bearer: String? = null, path: String = "/") =
    client.post(path) {
      contentType(ContentType.Application.Json)
      if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
      setBody(body)
    }

  @Test
  fun `initialize negotiates a supported protocol version`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", bearer = accessToken())
    assertEquals(HttpStatusCode.OK, resp.status)
    val body = mapper.readTree(resp.bodyAsText())
    assertEquals("2025-06-18", body["result"]["protocolVersion"].asText())
    assertEquals("sempods-mcp", body["result"]["serverInfo"]["name"].asText())
  }

  @Test
  fun `an anonymous initialize is met with the OAuth upgrade challenge (no public mode)`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNotNull(resp.headers["WWW-Authenticate"])
  }

  @Test
  fun `an anonymous tools list is met with the OAuth upgrade challenge (no public mode)`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNotNull(resp.headers["WWW-Authenticate"])
  }

  @Test
  fun `tools list advertises the authorize tool to an authenticated session`() = testApplication {
    install()
    val tools = mapper.readTree(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", bearer = accessToken()).bodyAsText())["result"]["tools"]
    val authorize = tools.first { it["name"].asText() == "authorize" }
    assertTrue(authorize["inputSchema"]["properties"].has("reauthorize"), "authorize must expose a reauthorize property")
  }

  @Test
  fun `tools list adds the read tools for an authenticated session`() = testApplication {
    install()
    val tools = mapper.readTree(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", bearer = accessToken()).bodyAsText())["result"]["tools"]
    val names = tools.map { it["name"].asText() }.toSet()
    assertTrue(names.contains("authorize"), names.toString())
    assertTrue(names.containsAll(hostedToolCatalog.readToolNames), "authenticated tools/list must advertise all read tools: $names")
    assertTrue(names.containsAll(hostedToolCatalog.writeToolNames), "authenticated tools/list must advertise all write tools: $names")
  }

  @Test
  fun `a read tool without a bearer triggers the OAuth upgrade challenge`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNotNull(resp.headers["WWW-Authenticate"])
  }

  @Test
  fun `a write tool without a bearer triggers the OAuth upgrade challenge`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_resource","arguments":{}}}""")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNotNull(resp.headers["WWW-Authenticate"])
  }

  @Test
  fun `read and write tools route to their dispatchers with a valid bearer`() = testApplication {
    install()
    val read = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""", bearer = accessToken())
    assertEquals("read:list_pods", mapper.readTree(read.bodyAsText())["result"]["content"][0]["text"].asText())
    val write = rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"create_resource","arguments":{}}}""", bearer = accessToken())
    assertEquals("write:create_resource", mapper.readTree(write.bodyAsText())["result"]["content"][0]["text"].asText())
  }

  @Test
  fun `authorize without a bearer triggers a 401 with a full challenge`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"authorize","arguments":{}}}""")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    val www = resp.headers["WWW-Authenticate"]
    assertNotNull(www)
    assertTrue("realm=" in www && "error=\"invalid_token\"" in www && "resource=" in www && "resource_metadata=" in www,
      "challenge must carry realm/error/resource/resource_metadata: $www")
    assertTrue("resource=\"https://mcp.test\"" in www, "default resource is the service root: $www")
    assertTrue("https://mcp.test/.well-known/oauth-protected-resource" in www,
      "resource_metadata should point at the root protected-resource metadata: $www")
  }

  @Test
  fun `a token minted for another profile is rejected on a different profile path (hard isolation)`() = testApplication {
    install()
    // The default-profile token works at the service root...
    val atRoot = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""", bearer = accessToken())
    assertEquals(HttpStatusCode.OK, atRoot.status)
    // ...but the same token is rejected on a named-profile path.
    val atOther = rpc(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""",
      bearer = accessToken(), path = "/private",
    )
    assertEquals(HttpStatusCode.Unauthorized, atOther.status)
    assertNotNull(atOther.headers["WWW-Authenticate"])
  }

  @Test
  fun `a profile token works on its own profile path`() = testApplication {
    install()
    val resp = rpc(
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""",
      bearer = accessToken(profile = "private"), path = "/private",
    )
    assertEquals("read:list_pods", mapper.readTree(resp.bodyAsText())["result"]["content"][0]["text"].asText())
    // The challenge on that path advertises the profile-scoped resource.
    val challenge = rpc(
      """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""",
      path = "/private",
    )
    assertEquals(HttpStatusCode.Unauthorized, challenge.status)
    val www = challenge.headers["WWW-Authenticate"]!!
    assertTrue("resource=\"https://mcp.test/private\"" in www, www)
    assertTrue("https://mcp.test/.well-known/oauth-protected-resource/private" in www, www)
  }

  @Test
  fun `a named-profile MCP endpoint tolerates a trailing slash`() = testApplication {
    install()
    val resp = rpc(
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}""",
      bearer = accessToken(profile = "private"), path = "/private/",
    )
    assertEquals("read:list_pods", mapper.readTree(resp.bodyAsText())["result"]["content"][0]["text"].asText())
  }

  @Test
  fun `the default sentinel path is not a routable alias of the root`() = testApplication {
    install()
    // `/default` must 404 (reserved), not serve the root default profile a second time.
    val resp = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", path = "/default")
    assertEquals(HttpStatusCode.NotFound, resp.status)
  }

  @Test
  fun `a present but invalid bearer is rejected before dispatch`() = testApplication {
    install()
    // A manipulated bearer must 401 (treated as invalid, not silently downgraded to anonymous).
    // With no public mode, anonymous initialize also 401s — but via a different path; this asserts
    // the invalid-bearer path specifically.
    val resp = rpc("""{"jsonrpc":"2.0","id":4,"method":"initialize"}""", bearer = "not-a-real-token")
    assertEquals(HttpStatusCode.Unauthorized, resp.status)
    assertNotNull(resp.headers["WWW-Authenticate"])
  }

  @Test
  fun `authorize with a valid bearer returns the session without side effects`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"authorize","arguments":{}}}""", bearer = accessToken())
    assertEquals(HttpStatusCode.OK, resp.status)
    val text = mapper.readTree(resp.bodyAsText())["result"]["content"][0]["text"].asText()
    assertTrue(text.startsWith("Signed in"), text)
  }

  @Test
  fun `reauthorize forces a fresh 401 then succeeds on the post-OAuth replay`() = testApplication {
    install()
    val token1 = accessToken(jti = "jti-1")
    // First reauthorize with a valid session → forced 401.
    val forced = rpc("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"authorize","arguments":{"reauthorize":true}}}""", bearer = token1)
    assertEquals(HttpStatusCode.Unauthorized, forced.status)
    // Client completes OAuth → new token (different jti). Replaying reauthorize now succeeds.
    val token2 = accessToken(jti = "jti-2")
    val replay = rpc("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"authorize","arguments":{"reauthorize":true}}}""", bearer = token2)
    assertEquals(HttpStatusCode.OK, replay.status)
    assertTrue(mapper.readTree(replay.bodyAsText())["result"]["content"][0]["text"].asText().startsWith("Signed in"))
  }

  @Test
  fun `reauthorize does not accept an older parallel token as the post-OAuth replay`() = testApplication {
    install()
    val current = accessToken(jti = "jti-current")
    // Force the challenge with the current session token.
    assertEquals(
      HttpStatusCode.Unauthorized,
      rpc("""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"authorize","arguments":{"reauthorize":true}}}""", bearer = current).status,
    )
    // A different token that was issued BEFORE the challenge must not pass as a fresh login.
    val older = accessToken(jti = "jti-older", issuedAt = Instant.now().minusSeconds(120))
    assertEquals(
      HttpStatusCode.Unauthorized,
      rpc("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"authorize","arguments":{"reauthorize":true}}}""", bearer = older).status,
    )
  }

  @Test
  fun `notifications are accepted with no body`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    assertEquals(HttpStatusCode.Accepted, resp.status)
  }

  // --- Per-user quota (M6.4) ---

  private fun toolsCall(id: Int) = """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"list_pods","arguments":{}}}"""

  @Test
  fun `an over-quota tools call is a JSON-RPC error on HTTP 200 and is audited`() = testApplication {
    install(userRateLimitPerMinute = 2)
    val user = "https://id.test/e/abc"
    assertEquals(HttpStatusCode.OK, rpc(toolsCall(1), bearer = accessToken(user)).status)
    assertEquals(HttpStatusCode.OK, rpc(toolsCall(2), bearer = accessToken(user)).status)
    val third = rpc(toolsCall(3), bearer = accessToken(user))
    // MCP-conformant: a protocol-level error, not an HTTP 429 and not a tool result.
    assertEquals(HttpStatusCode.OK, third.status)
    val body = mapper.readTree(third.bodyAsText())
    assertEquals(-32000, body["error"]["code"].asInt())
    assertTrue("Rate limit" in body["error"]["message"].asText(), body.toString())
    verify(exactly = 1) { auditLog.rateLimited(user, "default") }
  }

  @Test
  fun `tools list and initialize stay free while the tools-call quota is exhausted`() = testApplication {
    install(userRateLimitPerMinute = 1)
    assertEquals(HttpStatusCode.OK, rpc(toolsCall(1), bearer = accessToken()).status)
    // Quota exhausted — but the handshake methods are deliberately not throttled.
    val toolsList = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", bearer = accessToken())
    assertTrue(mapper.readTree(toolsList.bodyAsText())["result"]["tools"].size() > 0)
    val init = rpc("""{"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", bearer = accessToken())
    assertEquals("sempods-mcp", mapper.readTree(init.bodyAsText())["result"]["serverInfo"]["name"].asText())
  }

  @Test
  fun `an unauthenticated spray does not consume the quota (the gate sits behind auth)`() = testApplication {
    install(userRateLimitPerMinute = 1)
    // Anonymous and invalid-bearer calls 401 before the limiter — they must not drain the budget.
    repeat(3) { assertEquals(HttpStatusCode.Unauthorized, rpc(toolsCall(it)).status) }
    repeat(3) { assertEquals(HttpStatusCode.Unauthorized, rpc(toolsCall(it), bearer = "forged").status) }
    // The authenticated user still has its full budget.
    val resp = rpc(toolsCall(9), bearer = accessToken())
    assertEquals("read:list_pods", mapper.readTree(resp.bodyAsText())["result"]["content"][0]["text"].asText())
  }

  @Test
  fun `the authorize helper stays reachable when the tools-call quota is exhausted`() = testApplication {
    install(userRateLimitPerMinute = 1)
    // Distinct user: the reauthorize below writes to the shared mcp.reauthChallenges collection,
    // so a unique (clientId, user) keeps it from contaminating the reauthorize tests above.
    val user = "https://id.test/e/quota-authz"
    // Burn the budget on a pod tool...
    assertEquals(HttpStatusCode.OK, rpc(toolsCall(1), bearer = accessToken(user)).status)
    assertEquals(-32000, mapper.readTree(rpc(toolsCall(2), bearer = accessToken(user)).bodyAsText())["error"]["code"].asInt())
    // ...authorize is the re-consent / pod-connect escape hatch and must NOT be throttled, or a
    // user who exhausted the budget could never re-consent to recover.
    val authorize = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"authorize","arguments":{}}}""", bearer = accessToken(user))
    assertEquals(HttpStatusCode.OK, authorize.status)
    assertTrue(mapper.readTree(authorize.bodyAsText())["result"]["content"][0]["text"].asText().startsWith("Signed in"))
    // reauthorize (forces a fresh OAuth challenge) must also get through the exhausted budget.
    val reauth = rpc("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"authorize","arguments":{"reauthorize":true}}}""", bearer = accessToken(user))
    assertEquals(HttpStatusCode.Unauthorized, reauth.status)
    assertNotNull(reauth.headers["WWW-Authenticate"])
  }

  @Test
  fun `quota exhaustion on one profile leaves the sibling profile untouched`() = testApplication {
    install(userRateLimitPerMinute = 1)
    val user = "https://id.test/e/abc"
    // Exhaust the default profile's budget...
    assertEquals(HttpStatusCode.OK, rpc(toolsCall(1), bearer = accessToken(user)).status)
    val overQuota = mapper.readTree(rpc(toolsCall(2), bearer = accessToken(user)).bodyAsText())
    assertEquals(-32000, overQuota["error"]["code"].asInt())
    // ...the same user's named profile has its own budget (profiles are isolation bundles).
    val sibling = rpc(toolsCall(3), bearer = accessToken(user, profile = "private"), path = "/private")
    assertEquals("read:list_pods", mapper.readTree(sibling.bodyAsText())["result"]["content"][0]["text"].asText())
  }

  /**
   * The JSON-RPC envelope, byte for byte.
   *
   * These bodies are built from map literals today and will be built from shared DTOs after the
   * `sempods-mcp-core` move. Nothing else in the suite reads the raw response text — every other
   * assertion goes through `mapper.readTree`, which cannot see a field that appeared, a field that
   * vanished into `NON_NULL`, or a reordering. This test can.
   */
  @Test
  fun `the JSON-RPC envelope is byte-stable`() = testApplication {
    install()

    assertEquals(
      """{"jsonrpc":"2.0","id":5,"result":{}}""",
      rpc("""{"jsonrpc":"2.0","id":5,"method":"ping"}""", bearer = accessToken()).bodyAsText(),
    )
    assertEquals(
      """{"jsonrpc":"2.0","id":6,"result":{"resources":[]}}""",
      rpc("""{"jsonrpc":"2.0","id":6,"method":"resources/list"}""", bearer = accessToken()).bodyAsText(),
    )
    assertEquals(
      """{"jsonrpc":"2.0","id":7,"result":{"prompts":[]}}""",
      rpc("""{"jsonrpc":"2.0","id":7,"method":"prompts/list"}""", bearer = accessToken()).bodyAsText(),
    )
    assertEquals(
      """{"jsonrpc":"2.0","id":8,"error":{"code":-32601,"message":"Method not found: no/such/method"}}""",
      rpc("""{"jsonrpc":"2.0","id":8,"method":"no/such/method"}""", bearer = accessToken()).bodyAsText(),
    )
    assertEquals(
      """{"jsonrpc":"2.0","id":9,"error":{"code":-32601,"message":"Unknown tool: no_such_tool"}}""",
      rpc("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}""", bearer = accessToken()).bodyAsText(),
    )
    // `id` is null here even though the request carried one: the invalid-request branch answers
    // before the id is read off the envelope. JSON-RPC 2.0 5.1 permits it, and it is what this
    // service does today — pinned so the shared envelope keeps doing it rather than quietly
    // starting to echo the id back.
    assertEquals(
      """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Invalid Request"}}""",
      rpc("""{"jsonrpc":"1.0","id":10,"method":"ping"}""", bearer = accessToken()).bodyAsText(),
    )
  }

  /**
   * `capabilities` announces an empty `tools` object and must keep doing so. The pod-immanent MCP
   * sends `{"tools":{"listChanged":true}}` from the same field names — so once both sides build
   * this from one shared DTO, an omitted argument here silently adopts the pod's answer.
   */
  @Test
  fun `initialize announces tools capability as an empty object`() = testApplication {
    install()
    val body = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", bearer = accessToken()).bodyAsText()
    assertTrue(""""capabilities":{"tools":{}}""" in body, "capabilities must stay an empty tools object: $body")
  }

  /**
   * The full challenge string. Both MCP surfaces build this shape by hand today and `BearerChallenge`
   * makes it one implementation — at which point a swapped parameter would still satisfy the
   * "contains realm/error/resource" checks above, but not this.
   */
  @Test
  fun `the bearer challenge is exact`() = testApplication {
    install()
    val resp = rpc("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"authorize","arguments":{}}}""")
    assertEquals(
      "Bearer realm=\"sempods-mcp\", error=\"invalid_token\", " +
        "resource=\"https://mcp.test\", " +
        "resource_metadata=\"https://mcp.test/.well-known/oauth-protected-resource\"",
      resp.headers["WWW-Authenticate"],
    )
  }
}
