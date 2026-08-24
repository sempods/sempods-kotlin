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
import org.sempods.mcp.core.ToolCallResult
import org.sempods.mcp.auth.JwtTestSupport
import org.sempods.auth.core.JwtVerifier
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.core.ReauthorizeChallengeStore
import io.mockk.mockk
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.IgnoreTrailingSlash
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact `tools/list` payload this service answers with — the hosted counterpart of
 * `McpToolsListSnapshotTest` in `sempods-server`. Same reasoning: the descriptions are the
 * behavioural specification for the model on the other end, and `ToolCatalogTest` checks the
 * catalog's *structure* without ever looking at the bytes on the wire.
 *
 * Regenerate deliberately, never mechanically. On a mismatch the test writes what it saw to
 * `build/mcp/hosted-tools-list.actual.json` and names it in the failure — read that diff before you
 * copy it over the golden. There is no update flag on purpose.
 *
 * Authenticated, unlike the pod snapshot: this service has no anonymous mode.
 */
class McpToolsListSnapshotTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null
    private const val GOLDEN = "/mcp/hosted-tools-list.json"

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping MCP tools/list snapshot")
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

  private fun accessToken(): String = JwtTestSupport.sign(
    key,
    JWTClaimsSet.Builder()
      .issuer(base).subject("https://id.test/e/abc")
      .claim("client_id", "dyn:abc").claim("profile", "default").claim("scope", "")
      .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(3600)))
      .jwtID(UUID.randomUUID().toString()).build(),
  )

  private val noDispatch: suspend (String, com.fasterxml.jackson.databind.JsonNode?, ServiceBearerVerifier.Session) -> ToolCallResult =
    { _, _, _ -> ToolCallResult(content = emptyList()) }

  private fun ApplicationTestBuilder.installEndpoint() = application {
    install(IgnoreTrailingSlash)
    mcpEndpoint(
      config, verifier, ReauthorizeChallengeStore(db!!, SempodsMcpCollections.OAUTH_REAUTH_CHALLENGES), noDispatch, noDispatch, mapper,
      userRateLimiter = UserRateLimiter(0), auditLog = mockk<AuditLog>(relaxed = true),
    )
  }

  @Test
  fun `tools list payload matches the golden file`() = testApplication {
    installEndpoint()
    val response = client.post("/") {
      contentType(ContentType.Application.Json)
      header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
      setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
    }

    val tools = mapper.readTree(response.bodyAsText()).get("result").get("tools")
    val actual = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tools) + "\n"
    val expected = javaClass.getResource(GOLDEN)?.readText()

    if (expected != actual) {
      val dump = File("build/mcp/hosted-tools-list.actual.json")
      dump.parentFile.mkdirs()
      dump.writeText(actual)
      assertEquals(
        expected, actual,
        "tools/list changed. What the service answers now is in ${dump.absolutePath}; " +
          "the golden is src/test/resources$GOLDEN. Read the diff, then copy it over deliberately.",
      )
    }
  }
}
