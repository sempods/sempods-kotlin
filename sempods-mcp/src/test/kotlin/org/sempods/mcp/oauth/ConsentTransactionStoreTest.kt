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
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.sempods.mcp.SempodsMcpCollections

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class ConsentTransactionStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping ConsentTransactionStore test")
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

  private val store = ConsentTransactionStore(db!!)
  private val raw = db!!.getCollection(SempodsMcpCollections.OAUTH_CONSENT_TRANSACTIONS)

  private fun create(): String = store.create(
    ConsentTransactionStore.Transaction(
      user = "https://id.test/e/u1",
      profile = "default",
      clientId = "dyn:abc",
      clientLabel = "Test Client",
      redirectUri = "http://127.0.0.1:1/cb",
      clientState = null,
      scopes = setOf("read"),
      codeChallenge = "challenge",
      codeChallengeMethod = "S256",
    ),
  )

  @Test
  fun `the whole authorize context round-trips, and is spent once`() {
    val id = create()

    val txn = assertNotNull(store.consume(id))
    assertEquals("https://id.test/e/u1", txn.user)
    assertEquals("dyn:abc", txn.clientId)
    assertEquals("Test Client", txn.clientLabel)
    assertEquals(setOf("read"), txn.scopes)
    assertEquals("challenge", txn.codeChallenge)
    assertNull(txn.clientState)
    assertNull(store.consume(id), "second consume must lose (one-time use)")
  }

  @Test
  fun `peek does not consume, so the txn survives the inline pod-connect detour`() {
    val id = create()

    assertNotNull(store.peek(id))
    assertNotNull(store.peek(id), "peek must be repeatable")
    assertNotNull(store.consume(id))
    assertNull(store.peek(id), "after consume the txn is gone")
  }

  @Test
  fun `touch slides expiry forward and keeps the txn peekable`() {
    val id = create()
    // Age the txn to almost-expired, then touch it: the expiry must jump well into the future,
    // because the person is still working on the screen.
    val soon = System.currentTimeMillis() + 1_000
    raw.updateOne(Filters.eq("_id", sha256Hex(id)), Document("\$set", Document("expiresAt", Date(soon))))

    assertNotNull(store.touch(id))

    val stored = raw.find(Filters.eq("_id", sha256Hex(id))).first()!!.getDate("expiresAt").time
    assertTrue(stored > soon + 60_000, "expiry must slide well past the near-term deadline")
    assertNotNull(store.peek(id), "touch does not consume the txn")
    store.consume(id)
  }
}
