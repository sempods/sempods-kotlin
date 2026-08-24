package org.sempods.mcp.persist

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.sempods.mcp.SempodsMcpCollections

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class LeaseDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping LeaseDao test")
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

  private val dao = LeaseDao(db!!)
  private val raw = db!!.getCollection(SempodsMcpCollections.LEASES)

  private fun newLease() = "lease-" + UUID.randomUUID().toString().take(8)

  @Test
  fun `fresh lease is acquired and holds against a second holder`() {
    val lease = newLease()
    assertTrue(dao.tryAcquire(lease, "a", 60_000))
    assertFalse(dao.tryAcquire(lease, "b", 60_000))
    assertEquals("a", raw.find(Filters.eq("_id", lease)).first()!!.getString("holder"))
  }

  @Test
  fun `holder renews its own live lease`() {
    val lease = newLease()
    assertTrue(dao.tryAcquire(lease, "a", 60_000))
    assertTrue(dao.tryAcquire(lease, "a", 60_000), "renewal by the current holder must succeed")
    assertFalse(dao.tryAcquire(lease, "b", 60_000))
  }

  @Test
  fun `expired lease is taken over by another holder`() {
    val lease = newLease()
    // Insert an already-expired lease directly (a negative ttl through the API would also work,
    // but the raw row makes the expiry explicit).
    raw.insertOne(
      Document().apply {
        put("_id", lease)
        put("holder", "a")
        put("acquiredAt", Date(System.currentTimeMillis() - 120_000))
        put("expiresAt", Date(System.currentTimeMillis() - 60_000))
      },
    )
    assertTrue(dao.tryAcquire(lease, "b", 60_000))
    assertEquals("b", raw.find(Filters.eq("_id", lease)).first()!!.getString("holder"))
  }

  @Test
  fun `release frees the lease for the next holder, but only for the actual holder`() {
    val lease = newLease()
    assertTrue(dao.tryAcquire(lease, "a", 60_000))
    dao.release(lease, "b") // not the holder — must be a no-op
    assertFalse(dao.tryAcquire(lease, "b", 60_000))
    dao.release(lease, "a")
    assertTrue(dao.tryAcquire(lease, "b", 60_000))
  }

  @Test
  fun `concurrent acquires on an absent lease elect exactly one winner`() {
    val lease = newLease()
    val winners = (1..8).toList().parallelStream()
      .map { dao.tryAcquire(lease, "holder-$it", 60_000) }
      .toList()
      .count { it }
    assertEquals(1, winners, "exactly one concurrent acquire must win (DUPLICATE_KEY race path)")
  }
}
