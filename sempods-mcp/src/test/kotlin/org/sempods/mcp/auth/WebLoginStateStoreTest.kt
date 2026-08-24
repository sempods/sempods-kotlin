package org.sempods.mcp.auth

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
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.sempods.mcp.SempodsMcpCollections

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class WebLoginStateStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping WebLoginStateStore test")
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

  private val store = WebLoginStateStore(db!!)
  private val raw = db!!.getCollection(SempodsMcpCollections.OAUTH_WEB_LOGIN_STATES)

  @Test
  fun `the whole pending login round-trips, and is spent once`() {
    val state = store.newState()
    store.create(
      state,
      WebLoginStateStore.Pending(
        next = "/_system/ui?profile=work",
        codeVerifier = "v",
        nonce = "n",
        browserNonce = "pin",
      ),
    )

    val pending = assertNotNull(store.consume(state))
    assertEquals("/_system/ui?profile=work", pending.next)
    assertEquals("v", pending.codeVerifier)
    assertEquals("n", pending.nonce)
    // Without this the callback could not be tied to the browser that started the sign-in.
    assertEquals("pin", pending.browserNonce)
    assertNull(store.consume(state), "second consume must lose (one-time use)")
  }
}
