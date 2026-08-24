package org.sempods.mcp.persist

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class ProfileDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping ProfileDao test")
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

  private val user = "https://id.test/e/" + UUID.randomUUID()

  @Test
  fun `default profile is implicit - always exists and never stored`() {
    val dao = ProfileDao(db!!)
    assertTrue(dao.exists(user, PodKey.DEFAULT_PROFILE))
    assertEquals(listOf(PodKey.DEFAULT_PROFILE), dao.listForUser(user))
    // Creating the default is a no-op (it is never materialised).
    dao.create(user, PodKey.DEFAULT_PROFILE)
    assertEquals(listOf(PodKey.DEFAULT_PROFILE), dao.listForUser(user))
  }

  @Test
  fun `create then list and exist, default always first`() {
    val dao = ProfileDao(db!!)
    val u = "https://id.test/e/" + UUID.randomUUID()
    dao.create(u, "private")
    dao.create(u, "cron-agent")
    dao.create(u, "private") // idempotent
    assertTrue(dao.exists(u, "private"))
    assertFalse(dao.exists(u, "unknown"))
    assertEquals(listOf(PodKey.DEFAULT_PROFILE, "cron-agent", "private"), dao.listForUser(u))
  }

  @Test
  fun `profiles are isolated per user`() {
    val dao = ProfileDao(db!!)
    val a = "https://id.test/e/" + UUID.randomUUID()
    val b = "https://id.test/e/" + UUID.randomUUID()
    dao.create(a, "private")
    assertFalse(dao.exists(b, "private"))
    assertEquals(listOf(PodKey.DEFAULT_PROFILE), dao.listForUser(b))
  }

  @Test
  fun `delete removes a named profile but never the default`() {
    val dao = ProfileDao(db!!)
    val u = "https://id.test/e/" + UUID.randomUUID()
    dao.create(u, "private")
    dao.delete(u, "private")
    assertFalse(dao.exists(u, "private"))
    dao.delete(u, PodKey.DEFAULT_PROFILE)
    assertTrue(dao.exists(u, PodKey.DEFAULT_PROFILE))
  }
}
