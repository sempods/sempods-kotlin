package org.sempods.auth.core

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class AuthorizationCodeStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val COLLECTION = "authCodes"
    private val dbName = "sempods-auth-core-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping AuthorizationCodeStore test")
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

  private val store = AuthorizationCodeStore(db!!, COLLECTION)
  private val raw = db!!.getCollection(COLLECTION)

  private fun issue(): String = store.issue(
    subject = "https://id.test/e/u1",
    realm = "default",
    clientId = "dyn:abc",
    scopes = setOf("read", "write"),
    redirectUri = "http://127.0.0.1:1/cb",
    codeChallenge = "challenge",
    codeChallengeMethod = "S256",
  )

  @Test
  fun `issue then consume roundtrips the entry, one-time only`() {
    val code = issue()
    val entry = store.consume(code)
    assertNotNull(entry)
    assertEquals("https://id.test/e/u1", entry.subject)
    assertEquals(setOf("read", "write"), entry.scopes)
    assertEquals("challenge", entry.codeChallenge)
    assertNull(store.consume(code), "second consume must lose (one-time use)")
  }

  @Test
  fun `the code is stored hashed, never as plaintext id`() {
    val code = issue()
    assertNull(raw.find(Filters.eq("_id", code)).firstOrNull(), "plaintext code must not be an _id")
    assertNotNull(raw.find(Filters.eq("_id", sha256Hex(code))).firstOrNull())
    store.consume(code)
  }

  @Test
  fun `an expired but not yet TTL-reaped row is not consumable`() {
    // Insert a past-expiry row directly — the Mongo TTL reaper only runs periodically, so consume
    // must enforce expiry itself.
    val code = UUID.randomUUID().toString()
    raw.insertOne(
      Document().apply {
        put("_id", sha256Hex(code))
        put("subject", "u"); put("realm", "default"); put("clientId", "c")
        put("scopes", listOf("read")); put("redirectUri", "r")
        put("codeChallenge", null); put("codeChallengeMethod", null)
        put("expiresAt", Date(System.currentTimeMillis() - 1_000))
      },
    )
    assertNull(store.consume(code))
  }

  @Test
  fun `an unknown code is null`() {
    assertNull(store.consume("no-such-code"))
  }

  @Test
  fun `a stored code omits its empty fields rather than writing null`() {
    // `commons-mongo` pins this wire format, and it is not cosmetic: a written `null` changes what
    // `exists` and `size` mean on the collection later. Every code today has a null `nonce`, so
    // this would be the common case rather than the corner.
    val code = store.issue(
      subject = "u",
      realm = "default",
      clientId = "c",
      scopes = emptySet(),
      redirectUri = "https://pod.test/cb",
      codeChallenge = null,
      codeChallengeMethod = null,
      nonce = null,
    )
    val doc = requireNotNull(raw.find(Filters.eq("_id", sha256Hex(code))).first())

    for (absent in listOf("codeChallenge", "codeChallengeMethod", "nonce", "scopes")) {
      assertFalse(doc.containsKey(absent), "$absent should have been omitted, got: ${doc[absent]}")
    }
    assertTrue(doc.containsKey("subject"))
  }

  @Test
  fun `what is set round-trips`() {
    val code = store.issue(
      subject = "u",
      realm = "default",
      clientId = "c",
      scopes = setOf("openid"),
      redirectUri = "https://pod.test/cb",
      codeChallenge = "challenge",
      codeChallengeMethod = "S256",
      nonce = "n-1",
    )
    val entry = requireNotNull(store.consume(code))

    assertEquals(setOf("openid"), entry.scopes)
    assertEquals("challenge", entry.codeChallenge)
    assertEquals("n-1", entry.nonce)
  }
}
