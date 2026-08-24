package org.sempods.mcp.pods

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.sempods.mcp.SempodsMcpCollections
import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.crypto.testSecretCipher
import org.sempods.mcp.persist.InstanceId
import org.sempods.mcp.persist.LeaseDao
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.TokenVaultDao
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the sweep *picks*, which is the whole of this change: the warm tier follows use, the
 * preservation tier follows the refresh-token deadline, and neither reaches for the other's rows.
 *
 * Mongo-backed (the selections are Mongo filters, so mocking the DAO would test nothing); the
 * refresh itself is a mock, because `PodTokenProviderTest` already owns what a refresh does.
 * Skipped when Mongo is unreachable so the build stays green where it is absent.
 */
class TokenRefreshSchedulerTest {

  companion object {
    private const val MONGO_URL = "mongodb://localhost:27018"
    private val dbName = "sempods-mcp-test-" + UUID.randomUUID().toString().replace("-", "").take(10)
    private var mongoClient: MongoClient? = null
    private var db: MongoDatabase? = null

    @BeforeAll @JvmStatic
    fun setup() {
      assumeTrue(mongoReachable(), "local MongoDB not reachable — skipping TokenRefreshScheduler test")
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

    private const val DAY_MS = 24 * 60 * 60 * 1000L
  }

  private lateinit var vault: TokenVaultDao
  private lateinit var leases: LeaseDao
  private lateinit var provider: PodTokenProvider

  @BeforeEach
  fun freshCollections() {
    val database = db!!
    database.getCollection(SempodsMcpCollections.POD_TOKENS).drop()
    database.getCollection(SempodsMcpCollections.LEASES).drop()
    vault = TokenVaultDao(database, testSecretCipher())
    leases = LeaseDao(database)
    provider = mockk(relaxed = true)
  }

  private fun config(warmIdleSeconds: Long = 3600, preserveSeconds: Long = 30 * DAY_MS / 1000) =
    SempodsMcpConfig(
      port = 0, mongoUrl = MONGO_URL, mongoDbName = dbName, mcpBaseUrl = "http://localhost:8092",
      authIssuers = emptyList(),
      podTokenWarmIdleSeconds = warmIdleSeconds,
      podTokenFamilyPreserveSeconds = preserveSeconds,
    )

  private fun scheduler(
    config: SempodsMcpConfig = config(),
    instance: String = "replica-a",
    tierBudgetMs: Long = 60_000,
  ) = TokenRefreshScheduler(config, vault, provider, leases, InstanceId(instance), tierBudgetMs)

  private fun seed(
    pod: String,
    expiresAt: Date? = Date(System.currentTimeMillis() + 3_600_000),
    rotatedAt: Date = Date(),
    usedAt: Date? = null,
    refreshToken: String? = "rt",
  ) = PodKey("https://id.test/e/user", PodKey.DEFAULT_PROFILE, "https://pod.test/$pod").also {
    vault.upsert(PodTokens(it.user, it.profile, it.pod, "at", refreshToken, expiresAt, rotatedAt, usedAt))
  }

  /** The pods each tier handed to the provider, by the reason it gave. */
  private fun recordSweptPods(): Pair<MutableList<String>, MutableList<String>> {
    val warm = mutableListOf<String>()
    val preserved = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    val trigger = slot<RefreshTrigger>()
    coEvery { provider.refreshIfDue(capture(tokens), capture(trigger)) } answers {
      when (trigger.captured) {
        is RefreshTrigger.Expiring -> warm += tokens.captured.pod
        is RefreshTrigger.Preserving -> preserved += tokens.captured.pod
      }
    }
    return warm to preserved
  }

  @Test
  fun `the warm tier follows use, and the preservation tier follows the rotation deadline`() = runBlocking {
    val soon = Date(System.currentTimeMillis() + 60_000)
    val used = seed("used", expiresAt = soon, usedAt = Date())
    seed("idle", expiresAt = soon, usedAt = Date(System.currentTimeMillis() - 2 * 3_600_000))
    seed("never-used", expiresAt = soon, usedAt = null)
    val stale = seed("stale", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS), usedAt = null)

    val (warm, preserved) = recordSweptPods()
    scheduler().refreshDueTokens()

    assertEquals(listOf(used.pod), warm, "only a connection somebody used is worth keeping warm")
    assertEquals(listOf(stale.pod), preserved, "and only a family drifting toward its deadline is rotated blind")
  }

  @Test
  fun `a connection nobody uses costs the pods nothing until its family is due`() = runBlocking {
    // The row that used to be refreshed every 55 minutes forever: connected, never touched again.
    seed("forgotten", expiresAt = Date(System.currentTimeMillis() - 60_000), usedAt = null)

    val (warm, preserved) = recordSweptPods()
    scheduler().refreshDueTokens()

    assertTrue(warm.isEmpty() && preserved.isEmpty(), "an expired access token nobody wants is not work")
  }

  @Test
  fun `warm-keeping can be switched off entirely, leaving family preservation alone`() = runBlocking {
    val soon = Date(System.currentTimeMillis() + 60_000)
    seed("used", expiresAt = soon, usedAt = Date())
    val stale = seed("stale", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS), usedAt = Date())

    val (warm, preserved) = recordSweptPods()
    scheduler(config(warmIdleSeconds = 0)).refreshDueTokens()

    assertTrue(warm.isEmpty())
    assertEquals(listOf(stale.pod), preserved)
  }

  @Test
  fun `one pod's failure does not starve the rest of the pass`() = runBlocking {
    val long = Date(System.currentTimeMillis() - 40 * DAY_MS)
    listOf("a", "b", "c").forEach { seed(it, rotatedAt = long) }

    val swept = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    coEvery { provider.refreshIfDue(capture(tokens), any()) } answers {
      val pod = tokens.captured.pod
      swept += pod
      if (pod.endsWith("/a")) error("this pod is throttling us")
    }

    scheduler().refreshDueTokens()

    assertEquals(3, swept.size, "a throwing refresh is caught per token, not per pass")
  }

  @Test
  fun `the preservation pass stops at its budget and leaves the rest for the next tick`() = runBlocking {
    val long = Date(System.currentTimeMillis() - 40 * DAY_MS)
    listOf("a", "b", "c").forEach { seed(it, rotatedAt = long) }

    val swept = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    coEvery { provider.refreshIfDue(capture(tokens), any()) } coAnswers {
      swept += tokens.captured.pod
      delay(200)
    }

    // The budget is checked before each row, so the first one always runs and the second finds it
    // spent — which is the point: the warm tier gets its next turn instead of waiting out the herd.
    scheduler(tierBudgetMs = 50).refreshDueTokens()

    assertEquals(1, swept.size, "a budgeted pass yields rather than running the whole backlog")
  }

  @Test
  fun `a pod that eats the whole budget does not keep the rows behind it from ever being tried`() = runBlocking {
    // The selection comes back oldest-stamp-first, and a failed refresh moves no stamp — so without
    // a durable attempt mark, one slow pod at the head of it would spend every tick's budget and the
    // families behind it would expire while every pass completed and looked healthy.
    val wedged = seed("wedged", rotatedAt = Date(System.currentTimeMillis() - 50 * DAY_MS))
    val behind = seed("behind", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS))

    val swept = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    coEvery { provider.refreshIfDue(capture(tokens), any()) } coAnswers {
      swept += tokens.captured.pod
      if (tokens.captured.pod == wedged.pod) {
        delay(200)
        error("this pod never answers")
      }
    }

    val scheduler = scheduler(tierBudgetMs = 50)
    scheduler.refreshDueTokens()
    assertEquals(listOf(wedged.pod), swept, "the first tick gets no further than the wedged pod")

    scheduler.refreshDueTokens()
    assertEquals(
      listOf(wedged.pod, behind.pod), swept.take(2),
      "the next tick reaches the row behind it, before the wedged one is tried a second time",
    )
  }

  @Test
  fun `a warm pass that runs long still leaves preservation its own budget`() = runBlocking {
    // The warm tier runs first. Its budget is its own, and so is preservation's — anchored where
    // preservation starts, not where the sweep did. Anchored at the sweep, a slow warm pass would
    // hand this tier a deadline already in the past: zero rows, no marker, families expiring
    // because the *latency* tier had a bad tick.
    val slowWarm = seed("slow-warm", expiresAt = Date(System.currentTimeMillis() + 60_000), usedAt = Date())
    val stale = seed("stale", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS), usedAt = null)

    val swept = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    coEvery { provider.refreshIfDue(capture(tokens), any()) } coAnswers {
      swept += tokens.captured.pod
      if (tokens.captured.pod == slowWarm.pod) {
        delay(200) // one unreachable pod is four requests at the client's timeout
        error("this pod never answers")
      }
    }

    scheduler(tierBudgetMs = 50).refreshDueTokens()

    assertEquals(listOf(slowWarm.pod, stale.pod), swept, "preservation must run whatever the warm tier cost")
  }

  @Test
  fun `a backlog of failing pods is traversed once through before any of them is retried`() = runBlocking {
    // The sharper version of the case above: more slow failures than a tick can hold. An exclusion
    // window would let the earliest of them become eligible again before the rows behind them were
    // ever reached, and those families would expire. Ordering by the attempt mark cannot: attempting
    // a row sends it to the back, so the backlog is traversed once through, whatever its length.
    val wedged = (1..4).map { seed("wedged-$it", rotatedAt = Date(System.currentTimeMillis() - (50 - it) * DAY_MS)) }
    val behind = seed("behind", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS))

    val swept = mutableListOf<String>()
    val tokens = slot<PodTokens>()
    coEvery { provider.refreshIfDue(capture(tokens), any()) } coAnswers {
      swept += tokens.captured.pod
      delay(200)
      if (tokens.captured.pod != behind.pod) error("this pod never answers")
    }

    // A budget that fits exactly one row per tick, so the backlog can only advance by ordering.
    val scheduler = scheduler(tierBudgetMs = 50)
    repeat(5) { scheduler.refreshDueTokens() }

    assertEquals((wedged + behind).map { it.pod }, swept, "every row once, oldest deadline first, none twice")
  }

  @Test
  fun `only the lease holder sweeps`() = runBlocking {
    seed("stale", rotatedAt = Date(System.currentTimeMillis() - 40 * DAY_MS))
    assertTrue(leases.tryAcquire(LeaseDao.TOKEN_REFRESH_SWEEP, "replica-b", 60_000))

    val (warm, preserved) = recordSweptPods()
    val scheduler = scheduler(instance = "replica-a")
    scheduler.start()
    try {
      delay(500)
      assertTrue(warm.isEmpty() && preserved.isEmpty(), "a replica without the lease must not sweep")
    } finally {
      scheduler.stop()
    }

    // And the holder does sweep, on the same rows.
    scheduler(instance = "replica-b").refreshDueTokens()
    assertEquals(1, preserved.size)
  }
}
