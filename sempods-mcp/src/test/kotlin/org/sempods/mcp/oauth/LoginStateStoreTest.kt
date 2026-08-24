package org.sempods.mcp.oauth

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.sempods.commons.utils.HashUtil.sha256Hex
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
import org.sempods.mcp.SempodsMcpCollections

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class LoginStateStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping LoginStateStore test")
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

  private val store = LoginStateStore(db!!)
  private val raw = db!!.getCollection(SempodsMcpCollections.OAUTH_LOGIN_STATES)

  @Test
  fun `the whole authorize request round-trips, and is spent once`() {
    val state = store.newState()
    store.create(
      state,
      LoginStateStore.PendingAuthorize(
        profile = "default",
        clientId = "dyn:abc",
        redirectUri = "http://127.0.0.1:1/cb",
        clientState = "client-state",
        scopes = setOf("read"),
        codeChallenge = "challenge",
        codeChallengeMethod = "S256",
        oidcCodeVerifier = "verifier",
        oidcNonce = "nonce",
        browserNonce = "nonce-1",
      ),
    )

    val pending = assertNotNull(store.consume(state))
    assertEquals("dyn:abc", pending.clientId)
    assertEquals("client-state", pending.clientState)
    assertEquals(setOf("read"), pending.scopes)
    assertEquals("nonce-1", pending.browserNonce)
    // The two halves of the upstream flow. Both are what make the token that comes back belong to
    // this request rather than to some other login of the same person.
    assertEquals("verifier", pending.oidcCodeVerifier)
    assertEquals("nonce", pending.oidcNonce)
    assertNull(store.consume(state), "second consume must lose (one-time use)")
  }
}
