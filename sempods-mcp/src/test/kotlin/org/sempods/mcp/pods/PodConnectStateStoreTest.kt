package org.sempods.mcp.pods

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.sempods.commons.utils.HashUtil.sha256Hex
import org.sempods.mcp.crypto.testSecretCipher
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class PodConnectStateStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping PodConnectStateStore test")
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

  private val store = PodConnectStateStore(db!!, testSecretCipher())
  private val raw = db!!.getCollection(SempodsMcpCollections.OAUTH_POD_CONNECT_STATES)

  private fun create(returnTo: String? = null): String = store.create { expiresAt ->
    PodConnectStateStore.Pending(
      user = "https://id.test/e/u1",
      profile = "default",
      pod = "https://pod.test/p",
      metadata = PodOAuthMetadata(
        issuer = "https://pod.test/p/_system/auth",
        authorizationEndpoint = "https://pod.test/p/_system/auth/authorize",
        tokenEndpoint = "https://pod.test/p/_system/auth/token",
        registrationEndpoint = null,
        jwksUri = "https://pod.test/p/_system/auth/jwks",
      ),
      podClientId = "did:web:mcp.test",
      codeVerifier = "verifier-secret",
      redirectUri = "https://mcp.test/_system/ui/pods/callback",
      returnTo = returnTo,
      expiresAt = expiresAt,
    )
  }

  @Test
  fun `create then consume roundtrips the pending connect including metadata and returnTo, one-time only`() {
    val state = create(returnTo = "https://mcp.test/consent/resume")
    val pending = store.consume(state)
    assertNotNull(pending)
    assertEquals("https://pod.test/p/_system/auth", pending.metadata.issuer)
    assertNull(pending.metadata.registrationEndpoint)
    assertEquals("verifier-secret", pending.codeVerifier)
    assertEquals("https://mcp.test/consent/resume", pending.returnTo)
    assertNull(store.consume(state), "second consume must lose (one-time use)")
  }

  @Test
  fun `an undecryptable state row is treated as invalid, not a crash`() {
    val state = create()
    // A store with a different key cannot decrypt the codeVerifier (simulates a changed
    // MCP_SECRET_KEY mid-flow / tampered row) — consume must yield null, not throw.
    val wrongKeyStore = PodConnectStateStore(db!!, SecretCipher(ByteArray(32) { (it + 9).toByte() }))
    assertNull(wrongKeyStore.consume(state))
    assertNull(store.consume(state), "the bad row must still be consumed (one-time cleanup)")
  }

  @Test
  fun `the code verifier is ciphertext at rest and the state id is hashed`() {
    val state = create()
    assertNull(raw.find(Filters.eq("_id", state)).firstOrNull(), "plaintext state must not be an _id")
    val stored = raw.find(Filters.eq("_id", sha256Hex(state))).first()!!
    assertTrue(stored.getString("codeVerifier").startsWith("v1:"), "code verifier must be ciphertext")
    assertTrue(!stored.getString("codeVerifier").contains("verifier-secret"))
    store.consume(state)
  }
}
