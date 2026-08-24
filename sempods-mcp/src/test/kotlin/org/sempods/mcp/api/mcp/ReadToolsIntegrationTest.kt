package org.sempods.mcp.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.TokenVaultDao
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.wire.PodWireClient
import org.sempods.mcp.core.PodToolExecutor
import org.sempods.mcp.pods.PodOAuthClient
import org.sempods.mcp.pods.PodTokenProvider
import org.sempods.mcp.pods.PodUrlPolicy
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mongo-guarded end-to-end of the M3 read surface: two connected pods, fresh vault tokens, and the
 * [ReadTools] fan-out producing the per-pod envelope — including partial-error isolation (one pod
 * 200, the other 502) and the `targets` selector. Skipped when local Mongo is unreachable.
 */
class ReadToolsIntegrationTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB at $MONGO_URL not reachable — skipping")
      mongoClient = MongoClients.create(MONGO_URL).also { db = it.getDatabase(dbName) }
    }

    @AfterAll @JvmStatic
    fun teardown() {
      db?.drop()
      mongoClient?.close()
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

  private val mapper = jacksonObjectMapper()
  private val user = "https://id.test/e/user-1"
  private val profile = PodKey.DEFAULT_PROFILE
  private val session = ServiceBearerVerifier.Session(user, profile, "dyn:abc", emptySet(), "jti", null)

  private lateinit var server: ClientAndServer
  private lateinit var podA: String
  private lateinit var podB: String
  private lateinit var httpClient: HttpClient
  private lateinit var registry: ConnectionRegistryDao
  private lateinit var readTools: ReadTools
  private val auditLog = mockk<AuditLog>(relaxed = true)

  @BeforeEach
  fun each() {
    val database = db!!
    database.getCollection(SempodsMcpCollections.POD_TOKENS).drop()
    database.getCollection(SempodsMcpCollections.CONNECTIONS).drop()
    val vault = TokenVaultDao(database, testSecretCipher())
    registry = ConnectionRegistryDao(database)
    httpClient = HttpClient(CIO)
    val provider = PodTokenProvider(vault, registry, PodOAuthClient(
      SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules)),
      mapper, PodUrlPolicy(allowLocal = true),
    ), auditLog)
    val executor = PodToolExecutor(
      hostedToolCatalog,
      PodWireClient(
        SempodsHttpTransport(guard = SempodsOutboundGuard(PodUrlPolicy(allowLocal = true).rules)),
      ),
    )
    readTools = ReadTools(registry, provider, executor, mapper, "https://mcp.test", auditLog)

    server = ClientAndServer.startClientAndServer(0)
    podA = "http://localhost:${server.port}/a"
    podB = "http://localhost:${server.port}/b"
    // Pod A answers; pod B fails — proves one pod's failure does not poison the other.
    server.`when`(request().withMethod("GET").withPath("/a/_system/contexts"))
      .respond(response().withStatusCode(200).withBody("""{"contexts":[{"context_iri":"$podA/main","permissions":["read"]}],"writable_contexts":[]}"""))
    server.`when`(request().withMethod("GET").withPath("/b/_system/contexts"))
      .respond(response().withStatusCode(502))

    val soon = Date(System.currentTimeMillis() + 3_600_000)
    for (pod in listOf(podA, podB)) {
      registry.upsert(PodConnection(user, profile, pod, issuer = "$pod/_system/auth", podClientId = "dyn:x", scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date()))
      vault.upsert(PodTokens(user, profile, pod, accessToken = "tok", refreshToken = "rt", accessTokenExpiresAt = soon, updatedAt = Date()))
    }
  }

  @AfterEach
  fun stop() {
    server.stop()
    httpClient.close()
  }

  private suspend fun call(name: String, args: String?): JsonNode {
    val argsNode = args?.let { mapper.readTree(it) }
    val result = readTools.dispatch(name, argsNode, session)
    return mapper.readTree(result.content[0].text)
  }

  /**
   * Ages pod A's access token so the next tool call has to refresh it, and serves the discovery
   * documents that refresh needs — the token endpoint is read from the pod's own metadata, never
   * assumed.
   */
  private fun expireTokenAndDiscoverA() {
    val authBase = "$podA/_system/auth"
    TokenVaultDao(db!!, testSecretCipher()).upsert(
      PodTokens(
        user, profile, podA, accessToken = "stale", refreshToken = "rt",
        accessTokenExpiresAt = Date(System.currentTimeMillis() - 60_000), updatedAt = Date(),
      ),
    )
    server.`when`(request().withMethod("GET").withPath("/a/.well-known/oauth-protected-resource"))
      .respond(response().withStatusCode(200).withBody("""{"resource":"$podA","authorization_servers":["$authBase"]}"""))
    server.`when`(request().withMethod("GET").withPath("/a/_system/auth/.well-known/oauth-authorization-server"))
      .respond(
        response().withStatusCode(200).withBody(
          """{"issuer":"$authBase","authorization_endpoint":"$authBase/authorize","token_endpoint":"$authBase/token","registration_endpoint":"$authBase/register","jwks_uri":"$authBase/jwks.json"}""",
        ),
      )
  }

  private fun podEntry(envelope: JsonNode, pod: String): JsonNode =
    envelope["pods"].first { it["pod"].asText() == pod }

  @Test
  fun `list_pods returns the connected pods without contacting them`() = runBlocking {
    val body = call("list_pods", null)
    val pods = body["pods"].map { it["pod"].asText() }.toSet()
    assertEquals(setOf(podA, podB), pods)
    // `scopes` only carries the token feature scopes; a note must steer the caller to list_contexts
    // for the real per-context grants, so `scopes:[public-read]` is not misread as "no access".
    assertTrue(body.has("note") && "list_contexts" in body["note"].asText(),
      "list_pods must clarify scopes vs per-context grants: $body")
    assertEquals(listOf("public-read"), podEntry(body, podA)["scopes"].map { it.asText() })
  }

  @Test
  fun `list_pods surfaces a foreign pod identity and warns about it`() = runBlocking {
    // A pod that authorized us as a WebID different from the service user (its own identity provider).
    val foreignWebId = "https://voicesappdev.example/api/pod/u/42"
    registry.upsert(
      PodConnection(
        user = user, profile = profile, pod = "$podA", issuer = "$podA/_system/auth",
        podClientId = "did:web:mcp.test", scopes = setOf("public-read"),
        podSubject = foreignWebId, subjectVerified = false, createdAt = Date(), updatedAt = Date(),
      ),
    )
    val body = call("list_pods", null)
    val a = podEntry(body, podA)
    assertEquals(foreignWebId, a["pod_subject"].asText())
    assertTrue(a["foreign_identity"].asBoolean(), "a differing pod subject must be flagged foreign")
    assertFalse(a["subject_verified"].asBoolean(), "no JWKS → subject is unverified")
    assertEquals(user, a["similar_to"].asText(), "similar_to must weakly link the pod subject to the sempods WebID")
    assertTrue("foreign_identity" in body["note"].asText(),
      "the note must warn when any pod runs a foreign identity: ${body["note"].asText()}")
  }

  @Test
  fun `list_contexts fans out and isolates a failing pod`() = runBlocking {
    val body = call("list_contexts", null)
    val a = podEntry(body, podA)
    val b = podEntry(body, podB)
    assertTrue(a["ok"].asBoolean(), "pod A must succeed")
    assertEquals("$podA/main", a["result"]["contexts"][0]["context_iri"].asText())
    assertFalse(b["ok"].asBoolean(), "pod B (502) must report ok:false")
    assertEquals("pod_error", b["error"]["kind"].asText())
    assertTrue(b["error"]["message"].asText().isNotBlank())
    // The pod answered, so its status travels structurally — the same as on the write path, so a
    // caller branches on 403-vs-502 without regex-ing the message.
    assertEquals(502, b["error"]["status"].asInt())
    // Exactly one audit row per tools/call; one pod failed → outcome partial, both targets listed.
    verify(exactly = 1) { auditLog.toolCall(user, profile, "list_contexts", listOf(podA, podB).sorted(), "partial") }
  }

  @Test
  fun `a pod that is merely unwell reports pod_error, not reconnect this pod`() = runBlocking {
    // The user-visible half of the retryable/dead-grant distinction. `no_token` tells the person to
    // reconnect the pod at the web UI — wrong, and destructive, when the pod is only mid-deploy:
    // the reconnect rotates a refresh-token family that was never broken.
    expireTokenAndDiscoverA()
    server.`when`(request().withMethod("POST").withPath("/a/_system/auth/token"))
      .respond(response().withStatusCode(503).withBody("""{"error":"temporarily_unavailable"}"""))

    val a = podEntry(call("list_contexts", """{"targets":["$podA"]}"""), podA)

    assertFalse(a["ok"].asBoolean(), a.toString())
    assertEquals("pod_error", a["error"]["kind"].asText(), "a pod that answered is not a dead token: $a")
    // No `status`: the tool call never reached the pod's System layer — the token endpoint refused.
    // A status here would name a response the caller's request never got.
    assertTrue(a["error"]["status"] == null, "no pod answered this call: $a")
    assertFalse(
      "reconnect" in a["error"]["message"].asText(),
      "must not ask for a reconnect the pod does not need: ${a["error"]["message"].asText()}",
    )
    // The connection survives — nothing here says the grant is finished.
    assertTrue(registry.find(PodKey(user, profile, podA)) != null, "the connection must be left alone")
  }

  @Test
  fun `a pod that says the grant is finished does ask for a reconnect`() = runBlocking {
    // The other side of the same distinction: `invalid_grant` is the one code that means the
    // refresh token is gone (RFC 6749 §5.2), and then reconnecting is exactly the right advice.
    expireTokenAndDiscoverA()
    server.`when`(request().withMethod("POST").withPath("/a/_system/auth/token"))
      .respond(response().withStatusCode(400).withBody("""{"error":"invalid_grant"}"""))

    val a = podEntry(call("list_contexts", """{"targets":["$podA"]}"""), podA)

    assertFalse(a["ok"].asBoolean(), a.toString())
    assertEquals("no_token", a["error"]["kind"].asText(), a.toString())
    assertTrue("reconnect" in a["error"]["message"].asText(), a["error"]["message"].asText())
  }

  @Test
  fun `targets restricts the fan-out to the named pod`() = runBlocking {
    val body = call("list_contexts", """{"targets":["$podA"]}""")
    assertEquals(1, body["pods"].size())
    assertEquals(podA, body["pods"][0]["pod"].asText())
    verify(exactly = 1) { auditLog.toolCall(user, profile, "list_contexts", listOf(podA), "ok") }
  }

  @Test
  fun `an explicit empty targets selects no pods (tri-state)`() = runBlocking {
    // Absent targets → all pods (other tests); explicit [] → none, not a fall-back to all.
    val body = call("list_contexts", """{"targets":[]}""")
    assertEquals(0, body["pods"].size())
    verify(exactly = 1) { auditLog.toolCall(user, profile, "list_contexts", emptyList(), "ok") }
  }

  @Test
  fun `a missing required argument is a tool error, not a fan-out`() = runBlocking {
    val result = readTools.dispatch("sparql_select", null, session)
    assertEquals(true, result.isError)
    // Audited with the fixed label, never the message (which could embed argument values).
    verify(exactly = 1) { auditLog.toolCall(user, profile, "sparql_select", emptyList(), "error", "invalid_arguments") }
  }

  @Test
  fun `a malformed read IRI is a tool error too, not a pod that failed`() = runBlocking {
    // The reads used to skip the absolute-IRI check the writes have always run: the value blew up on
    // URI parsing inside the fan-out and came back as `pod_error`, blaming a pod that was never
    // asked. Now it is refused in front, like every other bad argument — and no pod is contacted.
    val result = readTools.dispatch("get_resource", mapper.readTree("""{"resource_iri":"not-an-iri"}"""), session)
    assertEquals(true, result.isError)
    assertTrue("must be an absolute IRI" in result.content[0].text!!, result.content[0].text!!)
    verify(exactly = 1) { auditLog.toolCall(user, profile, "get_resource", emptyList(), "error", "invalid_arguments") }
  }
}
