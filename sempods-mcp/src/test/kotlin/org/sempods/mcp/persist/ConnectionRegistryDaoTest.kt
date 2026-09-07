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
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class ConnectionRegistryDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping ConnectionRegistryDao test")
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

  private val dao = ConnectionRegistryDao(db!!)

  private fun newKey() = PodKey("https://id.test/e/" + UUID.randomUUID(), PodKey.DEFAULT_PROFILE, "https://pod.test/p")

  private fun seed(key: PodKey, podSubject: String, verified: Boolean) = dao.upsert(
    PodConnection(
      key.user, key.profile, key.pod, issuer = "https://pod.test/p/_system/auth",
      podClientId = "dyn:reconnected", scopes = setOf("public-read", "offline_access"),
      podSubject = podSubject, subjectVerified = verified,
      createdAt = Date(0), updatedAt = Date(0), podRedirectUri = "https://mcp.test/cb/agent",
    ),
  )

  @Test
  fun `a repair loses to a reconnect that rewrote the row it read`() {
    // The refresh read this row before a network round trip, and what it learned describes the
    // family that was current then. Landing it on a row a reconnect has moved on would pair the new
    // family's subject with the old one's verification — an unverified identity shown as verified,
    // which is the warning badge not appearing.
    val key = newKey()
    seed(key, podSubject = "https://pod.test/u/before", verified = false)
    val read = checkNotNull(dao.find(key))
    seed(key, podSubject = "https://pod.test/u/reconnected", verified = false)

    val landed = dao.recordSubjectIfUnchanged(read, "https://pod.test/u/confirmed", subjectVerified = true, at = Date(1_000))

    assertFalse(landed, "the row moved on; this repair must not land")
    val stored = checkNotNull(dao.find(key))
    assertEquals("https://pod.test/u/reconnected", stored.podSubject, "the reconnect holds the newer truth")
    assertFalse(stored.subjectVerified, "and its verification state, not the older family's")
  }

  @Test
  fun `recordSubject writes the identity it learned and leaves the rest of the row alone`() {
    // The caller read this row before a network round trip, and a reconnect writes it *before* the
    // token row it commits on — so what is here when the write lands can be a newer description
    // than the one that was read. Replacing the whole row would put the previous connection's
    // scopes, registration and issuer over a live one.
    val key = newKey()
    seed(key, podSubject = "https://pod.test/u/before", verified = false)
    val read = checkNotNull(dao.find(key))

    assertTrue(dao.recordSubjectIfUnchanged(read, "https://pod.test/u/confirmed", subjectVerified = true, at = Date(1_000)))

    val stored = checkNotNull(dao.find(key))
    assertEquals("https://pod.test/u/confirmed", stored.podSubject)
    assertTrue(stored.subjectVerified)
    assertEquals(Date(1_000), stored.updatedAt)
    assertEquals("dyn:reconnected", stored.podClientId, "a registration this write never read must survive it")
    assertEquals("https://mcp.test/cb/agent", stored.podRedirectUri)
    assertEquals(setOf("public-read", "offline_access"), stored.scopes)
    assertEquals("https://pod.test/p/_system/auth", stored.issuer)
    assertEquals(Date(0), stored.createdAt)
  }
}
