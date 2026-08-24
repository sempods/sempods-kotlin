package org.sempods.mcp.core

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
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class ReauthorizeChallengeStoreTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private const val COLLECTION = "reauthChallenges"
    private val dbName = "sempods-mcp-core-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping ReauthorizeChallengeStore test")
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

  /**
   * An hour ahead, so every `expiresAt` this test writes is in the future and the TTL monitor has
   * no opinion about the rows while the assertions run. Truncated to seconds because that is the
   * granularity the `iat` comparison works at.
   */
  private val baseTime: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3600)

  private var now: Instant = baseTime

  private val store = ReauthorizeChallengeStore(db!!, COLLECTION, clock = { now })
  private val raw = db!!.getCollection(COLLECTION)

  /** Each test gets its own realm, so the shared collection needs no cleanup between runs. */
  private val realm = "pod-" + UUID.randomUUID().toString().take(8)
  private val otherRealm = "pod-" + UUID.randomUUID().toString().take(8)

  /** iat at-or-after the recording moment — the legitimate replay case. */
  private val futureIat: Instant = baseTime.plusSeconds(2)

  /** iat strictly before the recording moment — a parallel-session token. */
  private val pastIat: Instant = baseTime.minusSeconds(60)

  private fun stored(realm: String, clientId: String?, sub: String?): Document? =
    raw.find(Filters.eq("_id", "$realm|${clientId.orEmpty()}|${sub.orEmpty()}")).firstOrNull()

  @Test
  fun `consumeIfReplay returns false when no challenge recorded`() {
    assertFalse(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-A", currentIssuedAt = futureIat))
  }

  @Test
  fun `consumeIfReplay returns false when current jti matches recorded one`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-A", currentIssuedAt = futureIat),
      "Same jti must not consume — user cancelled OAuth, no replay token issued",
    )
    assertNotNull(stored(realm, "client1", "user1"), "a non-replay must leave the challenge in place")
  }

  @Test
  fun `consumeIfReplay returns true when current jti differs and iat is after recording`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertTrue(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
    assertNull(stored(realm, "client1", "user1"), "the replay consumes the challenge")
  }

  @Test
  fun `consumeIfReplay rejects pre-existing token issued before challenge`() {
    // A parallel-session token (other tab, prior refresh-token rotation) has a different jti from
    // the challenged one but was not issued by the just-triggered OAuth flow. Its iat predates the
    // challenge. Must be rejected so incremental authorization is not silently skipped.
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = pastIat),
      "Parallel-session token (iat before challenge) must not satisfy the replay check",
    )
    assertNotNull(stored(realm, "client1", "user1"))
  }

  @Test
  fun `consumeIfReplay accepts token issued in the same second as recording`() {
    // JWT iat is rounded to seconds; recording happens at sub-second precision. The legitimate
    // replay case must still pass when the OAuth flow completes within the same wall-clock second
    // as the challenge — comparison is done at second precision, allowing equality.
    now = baseTime.plusMillis(750)
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertTrue(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = baseTime),
      "Token iat in the same second as recording must still be accepted",
    )
  }

  @Test
  fun `consumeIfReplay only succeeds once per recorded challenge`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertTrue(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-C", currentIssuedAt = futureIat),
      "Second consume must fail — challenge was already consumed",
    )
  }

  @Test
  fun `consumeIfReplay returns false after ttl expiry`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    now = baseTime.plus(Duration.ofMinutes(5)).plusSeconds(1)
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = now),
      "Expired challenge must not be consumed",
    )
  }

  @Test
  fun `an expired but not yet TTL-reaped challenge is not consumable`() {
    // The TTL monitor runs on its own schedule, so a row can outlive its expiry by minutes. The
    // filter has to re-check rather than trust the index.
    raw.insertOne(
      Document().apply {
        put("_id", "$realm|client1|user1")
        put("challengeTokenJti", "jti-A")
        put("challengedAtEpochSecond", baseTime.epochSecond)
        put("expiresAt", Date.from(baseTime.minusSeconds(1)))
      },
    )
    assertFalse(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
  }

  @Test
  fun `consumeIfReplay scopes by realm and clientId and sub`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertFalse(store.consumeIfReplay(otherRealm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat), "different realm")
    assertFalse(store.consumeIfReplay(realm, "client2", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat), "different client_id")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user2", currentJti = "jti-B", currentIssuedAt = futureIat),
      "different sub — another user sharing the client_id must not consume",
    )
    assertTrue(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
  }

  @Test
  fun `consumeIfReplay accepts an anonymous challenge from a fresh authenticated token`() {
    // The anonymous 401 has no client or subject yet — the OAuth flow it triggers is what produces
    // both. So the challenge is keyed by realm alone and the first fresh authenticated token for
    // that realm is allowed to consume it.
    store.record(realm, clientId = null, sub = null, jti = null)
    assertFalse(
      store.consumeIfReplay(otherRealm, clientId = "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat),
      "an anonymous challenge stays scoped to its realm",
    )
    assertTrue(store.consumeIfReplay(realm, clientId = "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
  }

  @Test
  fun `an anonymous challenge stores no jti field rather than a BSON null`() {
    // `docs/persistence.md`: a field that carries no information is not written. A missing
    // field satisfies the `$ne` on the jti, which is what makes the anonymous case work at all.
    store.record(realm, clientId = null, sub = null, jti = null)
    val doc = assertNotNull(stored(realm, null, null))
    assertFalse(doc.containsKey("challengeTokenJti"), "a null jti is omitted, not stored as BSON null")
  }

  @Test
  fun `consumeIfReplay treats null sub as its own bucket`() {
    store.record(realm, "client1", sub = null, jti = "jti-A")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat),
      "named-sub call must not consume a null-sub challenge",
    )
    assertTrue(store.consumeIfReplay(realm, "client1", sub = null, currentJti = "jti-B", currentIssuedAt = futureIat))
  }

  @Test
  fun `consumeIfReplay returns false when current jti is null`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertFalse(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = null, currentIssuedAt = futureIat))
    assertNotNull(stored(realm, "client1", "user1"))
  }

  @Test
  fun `consumeIfReplay returns false when currentIssuedAt is null`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = null),
      "Without iat we cannot prove the token was issued after the challenge",
    )
    assertNotNull(stored(realm, "client1", "user1"))
  }

  @Test
  fun `record overwrites the prior entry for the same key`() {
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    now = baseTime.plusSeconds(60)
    store.record(realm, "client1", sub = "user1", jti = "jti-B")
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = now),
      "Latest recorded jti must still block replay",
    )
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-C", currentIssuedAt = baseTime.plusSeconds(10)),
      "A token minted after the first challenge but before the second must not consume",
    )
    assertTrue(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-C", currentIssuedAt = now))
  }

  @Test
  fun `a challenge outlives the instance that recorded it`() {
    // The whole point of the shared store: the confirmation call may reach a different replica than the one that
    // issued the challenge, and it survives a restart of the one that did.
    store.record(realm, "client1", sub = "user1", jti = "jti-A")
    val otherReplica = ReauthorizeChallengeStore(db!!, COLLECTION, clock = { now })
    assertTrue(otherReplica.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-B", currentIssuedAt = futureIat))
    assertFalse(
      store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "jti-C", currentIssuedAt = futureIat),
      "and only one of them consumes it",
    )
  }

  @Test
  fun `a challenge recorded against a null jti is consumed by any fresh token`() {
    // The hosted surface records a null jti when the session carries no `jti` claim.
    store.record(realm, "client1", sub = "user1", jti = null)
    assertTrue(store.consumeIfReplay(realm, "client1", sub = "user1", currentJti = "any-jti", currentIssuedAt = futureIat))
  }
}
