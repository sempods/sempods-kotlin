package org.sempods.auth.core

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
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mechanism every parked flow shares, tested once instead of five times.
 *
 * Against a real MongoDB, because two of the four properties — the atomicity of `consume` and the
 * TTL index — are the database's, not this class's. Skipped rather than failed where Mongo is
 * absent, the repo convention.
 */
class OneTimeStoreTest {

  /** A payload with a nullable and a collection field, so the wire contract is exercised. */
  private data class Parked(val subject: String, val note: String?, val scopes: Set<String>)

  private fun store(ttl: Duration = Duration.ofMinutes(15)) = OneTimeStore(
    db = db!!,
    collectionName = COLLECTION,
    ttl = ttl,
    write = { put("subject", it.subject); putNotNull("note", it.note); putStrings("scopes", it.scopes) },
    read = {
      Parked(
        subject = getString("subject") ?: return@OneTimeStore null,
        note = getString("note"),
        scopes = getStringSet("scopes"),
      )
    },
  )

  private val parked = Parked(subject = "https://id.test/e/abc", note = null, scopes = setOf("read"))

  @Test
  fun `what is parked comes back, once`() {
    val store = store()
    val key = store.issue(parked)

    assertEquals(parked, store.consume(key))
    assertNull(store.consume(key), "a second callback must find nothing")
  }

  @Test
  fun `the key is not what is stored`() {
    val store = store()
    val key = store.issue(parked)

    // A database dump must hold nothing that resumes a flow.
    assertNull(raw().find(Filters.eq("_id", key)).firstOrNull(), "the plaintext key must not be an _id")
    assertNotNull(raw().find(Filters.eq("_id", sha256Hex(key))).firstOrNull())
    store.consume(key)
  }

  @Test
  fun `a key nobody issued is nothing`() {
    assertNull(store().consume("not-a-key"))
  }

  @Test
  fun `an expired row is not usable, however it is read`() {
    // Zero TTL: expired the moment it is written. The TTL index reaps on its own schedule, so a
    // row can outlive its expiry by minutes — every read has to check rather than trust it.
    val store = store(ttl = Duration.ZERO)
    val key = store.issue(parked)

    assertNull(store.consume(key), "consume")
    val second = store(ttl = Duration.ZERO).issue(parked)
    assertNull(store(ttl = Duration.ZERO).peek(second), "peek")
    assertNull(store(ttl = Duration.ZERO).touch(second), "touch must not resurrect one either")
  }

  @Test
  fun `peek leaves it where it is`() {
    val store = store()
    val key = store.issue(parked)

    assertEquals(parked, store.peek(key))
    assertEquals(parked, store.peek(key), "peeking twice is allowed — it is not the decision")
    assertEquals(parked, store.consume(key), "and it can still be spent afterwards")
  }

  @Test
  fun `touch slides the expiry out`() {
    val store = store(ttl = Duration.ofMinutes(15))
    val key = store.issue(parked)
    val before = expiryOf(key)

    Thread.sleep(20)
    assertEquals(parked, store.touch(key))

    assertTrue(expiryOf(key).after(before), "touch must persist a later expiry, was $before")
  }

  @Test
  fun `a row omits its empty fields rather than writing null`() {
    // `commons-mongo`'s contract, which `docs/persistence.md` states and which one of the
    // hand-written copies of this store did not keep. It changes what `exists` and `size` mean on
    // the collection later.
    val store = store()
    val key = store.issue(Parked(subject = "s", note = null, scopes = emptySet()))

    val doc = checkNotNull(raw().find(Filters.eq("_id", sha256Hex(key))).firstOrNull())
    assertTrue(!doc.containsKey("note"), "a null field must be omitted: $doc")
    assertTrue(!doc.containsKey("scopes"), "an empty collection must be omitted: $doc")
  }

  @Test
  fun `a row missing a field it needs reads as nothing, not as a crash`() {
    val store = store()
    val key = store.newKey()
    raw().insertOne(
      Document().apply {
        put("_id", sha256Hex(key))
        put("expiresAt", Date(System.currentTimeMillis() + 60_000))
        // no `subject` — the codec cannot build a payload from this
      },
    )

    assertNull(store.consume(key))
  }

  @Test
  fun `two keys are two flows`() {
    val store = store()
    assertTrue(store.newKey() != store.newKey())
  }

  private fun raw() = db!!.getCollection(COLLECTION)

  private fun expiryOf(key: String): Date =
    checkNotNull(raw().find(Filters.eq("_id", sha256Hex(key))).firstOrNull()).getDate("expiresAt")

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val COLLECTION = "test.oneTimeStore"
    private val dbName = "sempods-auth-core-test-" + UUID.randomUUID().toString().replace("-", "").take(10)

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
}
