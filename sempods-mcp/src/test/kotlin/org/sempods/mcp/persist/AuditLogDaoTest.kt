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
import kotlin.test.assertTrue
import org.sempods.mcp.SempodsMcpCollections

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent (M6.4). */
class AuditLogDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB (port 27018) not reachable — skipping AuditLogDao test")
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

  private fun freshUser() = "https://id.test/e/" + UUID.randomUUID()

  private fun event(
    user: String,
    profile: String = PodKey.DEFAULT_PROFILE,
    type: AuditEventType = AuditEventType.TOOL_CALL,
    ts: Date = Date(),
  ) = AuditEvent(
    ts = ts, user = user, profile = profile, type = type, outcome = "ok",
    tool = "list_pods", targets = listOf("https://pods.test/a"),
    expiresAt = Date(ts.time + 90L * 24 * 60 * 60 * 1000),
  )

  @Test
  fun `append then list round-trips every field`() {
    val dao = AuditLogDao(db!!)
    val user = freshUser()
    val ts = Date()
    dao.append(
      AuditEvent(
        ts = ts, user = user, profile = "private", type = AuditEventType.POD_TOKEN_REFRESH,
        outcome = "error", pod = "https://pods.test/a", detail = "issuer_mismatch",
        expiresAt = Date(ts.time + 1000),
      ),
    )
    val rows = dao.listFor(user)
    assertEquals(1, rows.size)
    val row = rows.single()
    assertEquals(user, row.user)
    assertEquals("private", row.profile)
    assertEquals(AuditEventType.POD_TOKEN_REFRESH, row.type)
    assertEquals("error", row.outcome)
    assertEquals("https://pods.test/a", row.pod)
    assertEquals("issuer_mismatch", row.detail)
    assertEquals(null, row.tool)
    assertEquals(null, row.targets)
    assertEquals(null, row.clientId)
  }

  @Test
  fun `listFor is isolated per user and optionally per profile`() {
    val dao = AuditLogDao(db!!)
    val a = freshUser()
    val b = freshUser()
    dao.append(event(a, profile = "default"))
    dao.append(event(a, profile = "private"))
    dao.append(event(b, profile = "default"))
    assertEquals(2, dao.listFor(a).size)
    assertEquals(1, dao.listFor(a, "private").size)
    assertEquals(1, dao.listFor(b).size)
    assertEquals(0, dao.listFor(freshUser()).size)
  }

  @Test
  fun `retention TTL index exists on expiresAt with expireAfterSeconds zero`() {
    AuditLogDao(db!!) // index creation happens in init
    val ttlIndex = db!!.getCollection(SempodsMcpCollections.AUDIT_LOG).listIndexes()
      .firstOrNull { (it["key"] as Document).containsKey("expiresAt") }
    assertTrue(ttlIndex != null, "expected a TTL index on expiresAt")
    assertEquals(0L, (ttlIndex["expireAfterSeconds"] as Number).toLong())
  }
}
