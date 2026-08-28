package org.sempods.mcp.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.sempods.commons.utils.UriEncodingUtil
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
import org.sempods.mcp.core.ToolCallResult
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
import java.net.URI
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mongo-guarded end-to-end of the M4 write surface: a single connected pod, a fresh vault token, and
 * [WriteTools] proxying create/add to the pod System layer — plus the single-target resolution, that
 * the service forwards rather than pre-judges where a write may land, and a pod precondition failure
 * surfacing as `ok:false`. Skipped without Mongo.
 */
class WriteToolsIntegrationTest {

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
  private val ctx get() = "$pod/main"

  private lateinit var server: ClientAndServer
  private lateinit var pod: String
  private lateinit var httpClient: HttpClient
  private lateinit var writeTools: WriteTools
  private lateinit var registry: ConnectionRegistryDao
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
    writeTools = WriteTools(registry, provider, executor, mapper, "https://mcp.test", auditLog)

    server = ClientAndServer.startClientAndServer(0)
    pod = "http://localhost:${server.port}/p"

    val resSlot = b64("$pod/thing")
    server.`when`(request().withMethod("PUT").withPath("/p/_system/resources/$resSlot"))
      .respond(response().withStatusCode(201).withHeader("ETag", "\"v1\""))
    val conflictSlot = b64("$pod/conflict")
    server.`when`(request().withMethod("PUT").withPath("/p/_system/resources/$conflictSlot"))
      .respond(response().withStatusCode(412).withBody("precondition failed"))
    val subjSlot = b64("$pod/thing")
    val predSlot = b64("https://schema.org/name")
    server.`when`(request().withMethod("POST").withPath("/p/_system/resources/$subjSlot/$predSlot"))
      .respond(response().withStatusCode(201).withHeader("ETag", "\"slot-v1\""))
    // PATCH stub that matches ONLY a properly quoted If-Match — proves the bare tag was normalized.
    val patchSlot = b64("$pod/patchme")
    server.`when`(request().withMethod("PATCH").withPath("/p/_system/resources/$patchSlot").withHeader("If-Match", "\"v1\""))
      .respond(response().withStatusCode(204).withHeader("ETag", "\"v2\""))

    registry.upsert(PodConnection(user, profile, pod, issuer = "$pod/_system/auth", podClientId = "dyn:x", scopes = setOf("public-read"), createdAt = Date(), updatedAt = Date()))
    vault.upsert(PodTokens(user, profile, pod, accessToken = "tok", refreshToken = "rt", accessTokenExpiresAt = Date(System.currentTimeMillis() + 3_600_000), updatedAt = Date()))
  }

  @AfterEach
  fun stop() {
    server.stop()
    httpClient.close()
  }

  private fun b64(iri: String) = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(iri))

  private suspend fun call(name: String, args: String): ToolCallResult = writeTools.dispatch(name, mapper.readTree(args), session)

  private fun envelope(result: ToolCallResult): JsonNode = mapper.readTree(result.content[0].text)

  @Test
  fun `create_resource writes to the single target pod and returns the etag`() = runBlocking {
    val res = call("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/thing","jsonld":{"@id":"$pod/thing","@type":"https://schema.org/Thing"}}""")
    val env = envelope(res)
    assertEquals(pod, env["pod"].asText())
    assertTrue(env["ok"].asBoolean(), env.toString())
    assertEquals(201, env["result"]["status"].asInt())
    assertEquals("\"v1\"", env["result"]["etag"].asText())
    // Exactly one audit row per write tools/call, targeting exactly the one pod.
    verify(exactly = 1) { auditLog.toolCall(user, profile, "create_resource", listOf(pod), "ok") }
  }

  @Test
  fun `a write as a foreign pod identity annotates the envelope`() = runBlocking {
    val foreignWebId = "https://voicesappdev.example/api/pod/u/7"
    registry.upsert(
      PodConnection(
        user, profile, pod, issuer = "$pod/_system/auth", podClientId = "did:web:mcp.test",
        scopes = setOf("public-read"), podSubject = foreignWebId, subjectVerified = false,
        createdAt = Date(), updatedAt = Date(),
      ),
    )
    val env = envelope(call("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/thing","jsonld":{"@id":"$pod/thing","@type":"https://schema.org/Thing"}}"""))
    assertTrue(env["ok"].asBoolean(), env.toString())
    assertTrue(env["foreign_identity"].asBoolean(), "write envelope must flag a foreign pod identity: $env")
    assertEquals(foreignWebId, env["pod_subject"].asText())
    assertEquals(user, env["similar_to"].asText(), "write envelope must carry the weak similar_to link")
  }

  @Test
  fun `add_property_value posts a single value to the slot`() = runBlocking {
    val res = call("add_property_value", """{"target":"$pod","context_iri":"$ctx","subject_iri":"$pod/thing","predicate_iri":"https://schema.org/name","value":{"@value":"Thing"}}""")
    val env = envelope(res)
    assertTrue(env["ok"].asBoolean(), env.toString())
    assertEquals("\"slot-v1\"", env["result"]["etag"].asText())
  }

  @Test
  fun `a write to an unconnected target is a tool error`() = runBlocking {
    val res = call("create_resource", """{"target":"http://localhost:1/other","context_iri":"http://localhost:1/other/main","resource_iri":"http://localhost:1/other/x","jsonld":{"@id":"http://localhost:1/other/x"}}""")
    assertEquals(true, res.isError)
    verify(exactly = 1) { auditLog.toolCall(user, profile, "create_resource", listOf("http://localhost:1/other"), "error", "not_connected") }
  }

  @Test
  fun `no context is pre-judged, every one is put to the pod`() = runBlocking {
    // What this pins is narrow on purpose: the request *arrives*. Whether the pod then accepts it
    // is the pod's business and a different test — `_system/sparql` below is not a context any real
    // pod would accept, and it is in this list precisely because this service used to refuse it
    // itself, before the pod was ever asked.
    //
    // Every context a pod does advertise lives under `_system/contexts/…` anyway, and a client
    // passes those IRIs back exactly as `GET {pod}/_system/contexts` handed them over. A guard here
    // that did not know the shape refused every write into a migrated context — which is what
    // happened, and why there is no guard now.
    for (context in listOf(
      "$pod/_system/contexts/contacts",
      "$pod/_system/contexts/apps/did:web:x/default",
      "$pod/_system/apps/did:web:x/default",
      "$pod/_system/sparql",
    )) {
      val res = call("create_resource", """{"target":"$pod","context_iri":"$context","resource_iri":"$pod/thing","jsonld":{"@id":"$pod/thing"}}""")
      assertTrue(res.isError != true, "must not be refused locally: $context — got ${envelope(res)}")
      // Asserted against the pod's own record rather than inferred from the response, so a future
      // short-circuit that fabricates a success cannot pass this.
      server.verify(
        request().withMethod("PUT").withPath("/p/_system/resources/${b64("$pod/thing")}")
          .withQueryStringParameter("context", context)
      )
    }
  }

  @Test
  fun `a pod refusal is surfaced as the pod gave it, not translated`() = runBlocking {
    // The flip side of dropping the guard: when the pod says no, that answer has to arrive intact.
    // A context the pod does not know is a 404 from the pod, not a tool error invented here.
    // No stub for this resource, so the pod answers 404 — the same shape a real pod returns for an
    // unknown context. What matters is that the answer comes from the pod at all.
    val res = call("create_resource", """{"target":"$pod","context_iri":"$pod/_system/sparql","resource_iri":"$pod/no-stub","jsonld":{"@id":"$pod/no-stub"}}""")
    val env = envelope(res)
    assertEquals(false, env["ok"].asBoolean(), env.toString())
    assertTrue(env.toString().contains("404"), "the pod's status has to survive: $env")
  }

  @Test
  fun `a control-plane subject is forwarded, the pod decides, and it allows one`() = runBlocking {
    // `PodReservedArea` was removed on purpose: a `_system` IRI is describable like any foreign
    // resource, and a statement *about* a context is ordinary data living in some context
    // (sempods-spec `spec/core/lod-crud.md` §4). This service refusing it was a policy the pod does
    // not have — the write still lands in a context the caller holds `#write` on.
    val subject = "$pod/_system/contexts/contacts"
    server.`when`(request().withMethod("POST").withPath("/p/_system/resources/${b64(subject)}/${b64("https://schema.org/name")}"))
      .respond(response().withStatusCode(201).withHeader("ETag", "\"about-v1\""))

    val res = call("add_property_value", """{"target":"$pod","context_iri":"$ctx","subject_iri":"$subject","predicate_iri":"https://schema.org/name","value":{"@value":"x"}}""")
    val env = envelope(res)
    assertTrue(env["ok"].asBoolean(), "the service must not refuse what the pod accepts: $env")
  }

  @Test
  fun `an empty precondition is a tool error, not a silently dropped header`() = runBlocking {
    val res = call("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/thing","jsonld":{"@id":"$pod/thing"},"if_none_match":""}""")
    assertEquals(true, res.isError)
  }

  @Test
  fun `a bare if_match is normalized to a quoted ETag before forwarding`() = runBlocking {
    // The PATCH stub matches only If-Match: "v1"; ok:true proves the bare "v1" was quoted, not
    // forwarded verbatim (which the pod would have dropped, running the patch unconditionally).
    val res = call("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/patchme","jsonld_patch":{"https://schema.org/text":[{"@value":"x"}]},"if_match":"v1"}""")
    val env = envelope(res)
    assertTrue(env["ok"].asBoolean(), env.toString())
    assertEquals(204, env["result"]["status"].asInt())
  }

  @Test
  fun `a malformed if_match (embedded quote) is a tool error`() = runBlocking {
    val res = call("update_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/patchme","jsonld_patch":{"https://schema.org/text":[{"@value":"x"}]},"if_match":"v\"1"}""")
    assertEquals(true, res.isError)
  }

  @Test
  fun `a malformed resource_iri is a tool error, never attributed to the pod`() = runBlocking {
    val res = call("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"not a valid iri","jsonld":{"@id":"x"}}""")
    assertEquals(true, res.isError)
  }

  @Test
  fun `a pod precondition failure surfaces as ok-false with kind and status`() = runBlocking {
    val res = call("create_resource", """{"target":"$pod","context_iri":"$ctx","resource_iri":"$pod/conflict","jsonld":{"@id":"$pod/conflict"},"if_none_match":"*"}""")
    val env = envelope(res)
    assertFalse(env["ok"].asBoolean(), env.toString())
    assertEquals("pod_error", env["error"]["kind"].asText())
    assertEquals(412, env["error"]["status"].asInt())
    // The audit detail carries the stable per-pod error kind, never the message.
    verify(exactly = 1) { auditLog.toolCall(user, profile, "create_resource", listOf(pod), "error", "pod_error") }
  }
}
