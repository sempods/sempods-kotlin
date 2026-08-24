package org.sempods.mcp.persist

import com.mongodb.ConnectionString
import com.mongodb.ExplainVerbosity
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.crypto.SecretCipher
import org.sempods.mcp.crypto.testSecretCipher
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mongo-backed; skipped when Mongo is unreachable so the build stays green where it is absent. */
class TokenVaultDaoTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping TokenVaultDao test")
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

  private val dao = TokenVaultDao(db!!, testSecretCipher())
  private val raw = db!!.getCollection(SempodsMcpCollections.POD_TOKENS)

  private fun keyFilter(k: PodKey) = Filters.and(
    Filters.eq("user", k.user), Filters.eq("profile", k.profile), Filters.eq("pod", k.pod),
  )

  private fun newKey() = PodKey("https://id.test/e/" + UUID.randomUUID(), PodKey.DEFAULT_PROFILE, "https://pod.test/p")

  @Test
  fun `upsert stores access and refresh tokens as ciphertext at rest`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "access-secret", "refresh-secret", Date(), Date()))

    val stored = raw.find(keyFilter(key)).first()!!
    assertTrue(stored.getString("accessToken").startsWith("v1:"), "access token should be ciphertext")
    assertTrue(stored.getString("refreshToken").startsWith("v1:"), "refresh token should be ciphertext")
    assertTrue(!stored.getString("accessToken").contains("access-secret"))

    val read = dao.find(key)!!
    assertEquals("access-secret", read.accessToken)
    assertEquals("refresh-secret", read.refreshToken)
  }

  @Test
  fun `null refresh token stays absent`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", null, Date(), Date()))
    assertNull(raw.find(keyFilter(key)).first()!!.getString("refreshToken"))
    assertNull(dao.find(key)!!.refreshToken)
  }

  @Test
  fun `refresh claim wins exactly once across two holders and expires`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), Date()))

    val until = Date(System.currentTimeMillis() + 60_000)
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until), "re-claim by the current holder must succeed")
    assertTrue(!dao.tryClaimRefresh(key, "replica-b", until), "live claim must hold against another holder")

    // Expired claim is reclaimable by anyone.
    raw.updateOne(keyFilter(key), Updates.set("refreshClaimedUntil", Date(System.currentTimeMillis() - 1_000)))
    assertTrue(dao.tryClaimRefresh(key, "replica-b", until))
  }

  @Test
  fun `release only works for the actual holder, upsert clears the claim implicitly`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), Date()))
    val until = Date(System.currentTimeMillis() + 60_000)
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))

    dao.releaseRefreshClaim(key, "replica-b") // not the holder — no-op
    assertTrue(!dao.tryClaimRefresh(key, "replica-b", until))

    dao.releaseRefreshClaim(key, "replica-a")
    assertTrue(dao.tryClaimRefresh(key, "replica-b", until))

    // Persisting a refreshed row (full-document replace) drops the claim fields — the success
    // path's implicit release.
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a2", "r2", Date(), Date()))
    assertNull(raw.find(keyFilter(key)).first()!!.getString("refreshClaimedBy"))
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))
  }

  @Test
  fun `a claim on a missing row is refused`() {
    assertTrue(!dao.tryClaimRefresh(newKey(), "replica-a", Date(System.currentTimeMillis() + 60_000)))
  }

  @Test
  fun `replaceIfClaimedBy persists only while the claim is still ours`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), Date()))
    val until = Date(System.currentTimeMillis() + 60_000)
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))

    // Claim held → the refreshed row lands, and the replace drops the claim fields (the release).
    assertTrue(dao.replaceIfClaimedBy(PodTokens(key.user, key.profile, key.pod, "a2", "r2", Date(), Date()), "replica-a"))
    assertEquals("a2", dao.find(key)!!.accessToken)
    assertNull(raw.find(keyFilter(key)).first()!!.getString("refreshClaimedBy"))

    // A re-connect replaced the row (upsert clears any claim) while a refresh was in flight — the
    // stale rotation must lose and the re-connect's tokens must survive.
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "reconnect-access", "reconnect-refresh", Date(), Date()))
    assertTrue(!dao.replaceIfClaimedBy(PodTokens(key.user, key.profile, key.pod, "stale", "stale", Date(), Date()), "replica-a"))
    assertEquals("reconnect-access", dao.find(key)!!.accessToken)

    // A disconnect deleted the row — the late refresh must not resurrect it.
    assertTrue(dao.tryClaimRefresh(key, "replica-a", until))
    dao.delete(key)
    assertTrue(!dao.replaceIfClaimedBy(PodTokens(key.user, key.profile, key.pod, "zombie", "zombie", Date(), Date()), "replica-a"))
    assertNull(dao.find(key))
  }

  @Test
  fun `an undecryptable row is treated as absent, not a crash`() {
    val key = newKey()
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "access-secret", "refresh-secret", Date(), Date()))

    // A DAO with a different key cannot decrypt the row — find returns null (→ "reconnect this pod").
    val wrongKeyDao = TokenVaultDao(db!!, SecretCipher(ByteArray(32) { (it + 9).toByte() }))
    assertNull(wrongKeyDao.find(key))
    // And the refresh sweep skips it instead of throwing (one bad row must not wedge the sweep).
    val everything = Date(System.currentTimeMillis() + 3_600_000)
    val due = wrongKeyDao.findNotRotatedSince(everything, limit = 100)
    assertTrue(due.rows.none { it.pod == key.pod })
    // And it is named rather than dropped, so the sweep can mark it and stop it masking the queue.
    assertTrue(due.unreadable.any { it.pod == key.pod }, "an unreadable row must still be identifiable")
  }

  @Test
  fun `the warm selection takes a recently used row with a near expiry, and nothing else`() {
    val soon = Date(System.currentTimeMillis() + 60_000)
    val far = Date(System.currentTimeMillis() + 3_600_000)
    val justNow = Date()
    val longAgo = Date(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

    val warm = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", soon, justNow, justNow)) }
    val idle = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", soon, justNow, longAgo)) }
    val neverUsed = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", soon, justNow, null)) }
    val notExpiring = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", far, justNow, justNow)) }
    // The row the old selection handed back on every tick only for the provider to skip it.
    val unknownExpiry = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", null, justNow, justNow)) }
    val unrefreshable = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", null, soon, justNow, justNow)) }

    val pods = dao.findExpiringBefore(
      cutoff = Date(System.currentTimeMillis() + 300_000),
      usedSince = Date(System.currentTimeMillis() - 3_600_000),
      limit = 100,
    ).map { it.user }

    assertTrue(pods.contains(warm.user))
    listOf(idle, neverUsed, notExpiring, unknownExpiry, unrefreshable).forEach {
      assertTrue(!pods.contains(it.user), "warm tier must not select ${'$'}{it.user}")
    }
  }

  @Test
  fun `the preservation selection goes by rotation stamp, whatever the access token says`() {
    val longAgo = Date(System.currentTimeMillis() - 40 * 24 * 60 * 60 * 1000L)
    val justNow = Date()
    val far = Date(System.currentTimeMillis() + 3_600_000)

    // Never used, access token nowhere near expiry — invisible to the warm tier, and exactly what
    // this one exists for.
    val stale = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", far, longAgo, null)) }
    val unknownExpiry = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", null, longAgo, null)) }
    val freshlyRotated = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", far, justNow, null)) }
    val unrefreshable = newKey().also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", null, far, longAgo, null)) }

    val pods = dao.findNotRotatedSince(
      Date(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L),
      limit = 100,
    ).rows.map { it.user }

    assertTrue(pods.contains(stale.user))
    assertTrue(pods.contains(unknownExpiry.user), "an unknown expiry has a family deadline all the same")
    assertTrue(!pods.contains(freshlyRotated.user))
    assertTrue(!pods.contains(unrefreshable.user), "nothing to rotate with")
  }

  @Test
  fun `touchLastUsed moves the marker only past the throttle, and disturbs nothing else`() {
    val key = newKey()
    val rotatedAt = Date(System.currentTimeMillis() - 10_000)
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), rotatedAt, null))
    val claimUntil = Date(System.currentTimeMillis() + 60_000)
    assertTrue(dao.tryClaimRefresh(key, "replica-a", claimUntil))

    val first = Date()
    assertTrue(dao.touchLastUsed(key, first, ifOlderThan = Date(first.time - 60_000)), "an unmarked row is always marked")
    assertEquals(first, dao.find(key)!!.lastUsedAt)

    // A second call inside the throttle window is a no-op, which is what keeps a burst of tool
    // calls from costing a write apiece.
    assertTrue(!dao.touchLastUsed(key, Date(first.time + 1_000), ifOlderThan = Date(first.time - 60_000)))
    assertEquals(first, dao.find(key)!!.lastUsedAt)

    val later = Date(first.time + 120_000)
    assertTrue(dao.touchLastUsed(key, later, ifOlderThan = Date(later.time - 60_000)))
    assertEquals(later, dao.find(key)!!.lastUsedAt)

    // The marker is not the rotation stamp, and it does not release a claim.
    assertEquals(rotatedAt, dao.find(key)!!.updatedAt)
    assertTrue(!dao.tryClaimRefresh(key, "replica-b", claimUntil), "the claim must have survived the touch")
  }

  @Test
  fun `both sweep selections are served by an index, and read no more than they return`() {
    // The sweep runs on a clock, so a query the planner cannot serve from an index is a full pass
    // over every connected pod's row on every tick. Explained as the sweep actually issues it —
    // filter, ordering and bound — because an explain of the filter alone would pass happily while
    // the ordering forced a blocking sort over the whole selection.
    val collection = "podTokensExplain" + UUID.randomUUID().toString().take(8)
    val explained = TokenVaultDao(db!!, testSecretCipher(), collection)
    val key = newKey()
    explained.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), Date(), Date()))
    // Rows nothing can ever rotate, old enough to sort ahead of everything: in a plain index they
    // would be examined on every tick and discarded, so the batch bound would stop bounding reads.
    repeat(5) {
      val dead = newKey()
      explained.upsert(PodTokens(dead.user, dead.profile, dead.pod, "a", null, Date(), Date(0), Date(0)))
    }

    listOf(
      "warm" to explained.expiringBeforeQuery(cutoff = Date(), usedSince = Date(0), limit = 500),
      "preservation head" to explained.notRotatedSinceQuery(Date(), limit = 500, attempted = false),
      "preservation tail" to explained.notRotatedSinceQuery(Date(), limit = 500, attempted = true),
    ).forEach { (tier, query) ->
      val explain = query.explain(ExplainVerbosity.EXECUTION_STATS)
      val plan = explain.get("queryPlanner", Document::class.java).toJson()
      assertTrue(plan.contains("IXSCAN"), "$tier: expected an index scan, got: $plan")
      assertTrue(!plan.contains("COLLSCAN"), "$tier: expected no collection scan, got: $plan")
      assertTrue(!plan.contains("SORT"), "$tier: expected the index to carry the order, got: $plan")

      // The part the plan shape alone does not say: an unrefreshable row must not even be looked at.
      // `IXSCAN` with a residual filter would pass every assertion above and still read all six.
      val stats = explain.get("executionStats", Document::class.java)
      val returned = stats.getInteger("nReturned")
      assertEquals(returned, stats.getInteger("totalKeysExamined"), "$tier: read more index keys than it returned")
      assertEquals(returned, stats.getInteger("totalDocsExamined"), "$tier: fetched more documents than it returned")
    }
  }

  @Test
  fun `the preservation selection is round-robin - an attempted row goes to the back`() {
    val stale = Date(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L)
    fun seed(pod: String, rotatedAt: Date) = PodKey("u-" + UUID.randomUUID(), PodKey.DEFAULT_PROFILE, pod)
      .also { dao.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", Date(), rotatedAt, null)) }

    // Oldest rotation first among never-attempted rows: closest to its deadline goes first.
    val oldest = seed("https://pod.test/oldest", Date(System.currentTimeMillis() - 50 * 24 * 60 * 60 * 1000L))
    val newer = seed("https://pod.test/newer", Date(System.currentTimeMillis() - 40 * 24 * 60 * 60 * 1000L))
    fun order() = dao.findNotRotatedSince(stale, limit = 100).rows
      .filter { it.user in setOf(oldest.user, newer.user) }.map { it.user }

    assertEquals(listOf(oldest.user, newer.user), order())

    // Attempting the head sends it to the back — even though its rotation stamp has not moved,
    // which is exactly the case a failed refresh leaves behind. Nothing here waits out a window.
    dao.markRefreshAttempted(oldest, Date())
    assertEquals(listOf(newer.user, oldest.user), order(), "an attempted row must not be tried again first")

    dao.markRefreshAttempted(newer, Date(System.currentTimeMillis() + 1_000))
    assertEquals(listOf(oldest.user, newer.user), order(), "and then the least-recently-attempted leads again")
  }

  @Test
  fun `an unreadable row at the head neither ends the head nor masks what is behind it`() {
    val stale = Date(System.currentTimeMillis() - 30 * 24 * 60 * 60 * 1000L)
    val longAgo = Date(System.currentTimeMillis() - 50 * 24 * 60 * 60 * 1000L)
    val collection = "podTokensUnreadable" + UUID.randomUUID().toString().take(8)
    val readable = TokenVaultDao(db!!, testSecretCipher(), collection)
    // Written under a different key, so this DAO cannot decrypt it — and it sorts first, being the
    // oldest rotation with no attempt mark.
    val blind = TokenVaultDao(db!!, SecretCipher(ByteArray(32) { (it + 9).toByte() }), collection)
    val opaque = newKey().also { blind.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", Date(), longAgo, null)) }
    val behind = newKey().also {
      readable.upsert(PodTokens(it.user, it.profile, it.pod, "a", "r", Date(), Date(System.currentTimeMillis() - 40 * 24 * 60 * 60 * 1000L), null))
    }

    // A batch of one: the head is full, so the tail must not be consulted — and the row behind the
    // unreadable one must still be reachable once that one has been marked.
    val first = readable.findNotRotatedSince(stale, limit = 1)
    assertTrue(first.rows.isEmpty())
    assertEquals(listOf(opaque.pod), first.unreadable.map { it.pod })

    readable.markRefreshAttempted(opaque, Date())
    val second = readable.findNotRotatedSince(stale, limit = 1)
    assertEquals(listOf(behind.pod), second.rows.map { it.pod }, "marking the unreadable row must free the head")
  }

  @Test
  fun `a claimed replace carries the use marker forward`() {
    val key = newKey()
    val usedAt = Date(System.currentTimeMillis() - 5_000)
    dao.upsert(PodTokens(key.user, key.profile, key.pod, "a", "r", Date(), Date(), usedAt))
    assertTrue(dao.tryClaimRefresh(key, "replica-a", Date(System.currentTimeMillis() + 60_000)))

    val rotated = dao.find(key)!!.copy(accessToken = "a2", refreshToken = "r2", updatedAt = Date())
    assertTrue(dao.replaceIfClaimedBy(rotated, "replica-a"))

    assertEquals("a2", dao.find(key)!!.accessToken)
    assertEquals(usedAt, dao.find(key)!!.lastUsedAt, "a rotation must not make a warm connection look idle")
  }
}
