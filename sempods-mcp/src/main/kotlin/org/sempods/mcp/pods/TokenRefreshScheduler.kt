package org.sempods.mcp.pods

import org.sempods.mcp.SempodsMcpConfig
import org.sempods.mcp.persist.InstanceId
import org.sempods.mcp.persist.LeaseDao
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.TokenVaultDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/** The tick, and the floor under it. A top-level function so the budget below can default off it. */
private fun tickIntervalMs(config: SempodsMcpConfig) =
  (config.podTokenRefreshWindowSeconds * 1000 / 2).coerceAtLeast(30_000)

/**
 * Background loop that keeps connected pods reachable headlessly (the "stay connected" half of M2),
 * on the clock the thing it protects actually runs on.
 *
 * What dies from disuse is the pod's **refresh-token family** — ninety days, reset in full by any
 * single rotation, and the same `/token` call keeps the service's DCR registration at that pod alive
 * against the same boundary. An expired **access** token costs no person and no dialog:
 * [PodTokenProvider.validAccessToken] renews it on demand, on a path that is built, tested and on
 * every read anyway. So the sweep runs two tiers, and only the first of them is about latency:
 *
 *  - **Warm** ([SempodsMcpConfig.podTokenWarmIdleSeconds]) — a connection used recently is renewed
 *    ahead of expiry exactly as before, so an active agent never pays the four sequential pod
 *    requests a cold rotation costs. Past the idle threshold that trade turns: warm-keeping spends
 *    one rotation per token lifetime to save one, once.
 *  - **Preservation** ([SempodsMcpConfig.podTokenFamilyPreserveSeconds]) — every refreshable
 *    connection is rotated on a long cadence regardless of access-token expiry, which is the only
 *    cadence the ninety-day deadline asks for. The service cannot read the pod's refresh-token TTL
 *    (RFC 6749 has no field for it), so this is a deliberately conservative guess against a value
 *    the pod owns.
 *
 * The load therefore scales with **use** rather than with inventory, which matters because
 * inventory only grows and the traffic lands at the pods — machines people host themselves — not at
 * the service generating it.
 *
 * Multi-instance (M6.3): every replica runs the loop, but each tick first tries the
 * [LeaseDao.TOKEN_REFRESH_SWEEP] lease — only the holder sweeps, so N replicas do not scan the
 * vault (and re-discover pod metadata) N times per interval. The lease is an efficiency layer,
 * not the correctness one: correctness against double-refresh lives in the per-token claims
 * inside [PodTokenProvider], so a lease expiring mid-sweep (overlapping sweepers) is harmless.
 * [LeaseDao.tryAcquire] doubles as the renewal, and a dying leader's lease simply expires
 * (≤ [leaseTtlMs]) before another replica takes over.
 */
// TODO: the sweep runs outside any request, so its pod calls carry no `traceparent` — one failed
//  refresh cannot be followed into the pod's log. Binding a fresh trace per sweep iteration
//  (`TraceContextHolder.with(TraceContext.random())`, or the coroutine element from
//  `commons-ktor`) would close that; see `docs/request-tracing.md` §Scope.
class TokenRefreshScheduler(
  private val config: SempodsMcpConfig,
  private val tokenVaultDao: TokenVaultDao,
  private val podTokenProvider: PodTokenProvider,
  private val leaseDao: LeaseDao,
  private val instanceId: InstanceId,
  /**
   * How long each tier may spend per tick. Half a tick each, so a sweep lands inside its own
   * interval and neither tier can run away with the other's turn.
   */
  private val tierBudgetMs: Long = tickIntervalMs(config) / 2,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null
  private val intervalMs = tickIntervalMs(config)

  fun start() {
    if (job != null) return
    // Outlive one missed tick so leadership does not flap, but expire fast enough that a dead
    // leader's replacement steps in well inside the refresh window.
    val leaseTtlMs = (2 * intervalMs).coerceAtLeast(90_000)
    job = scope.launch {
      logger.info {
        "Pod token refresh loop started (window=${config.podTokenRefreshWindowSeconds}s, " +
          "warmIdle=${config.podTokenWarmIdleSeconds}s, preserve=${config.podTokenFamilyPreserveSeconds}s, " +
          "interval=${intervalMs}ms, instance=${instanceId.value})"
      }
      while (isActive) {
        if (leaseDao.tryAcquire(LeaseDao.TOKEN_REFRESH_SWEEP, instanceId.value, leaseTtlMs)) {
          runCatching { refreshDueTokens() }.onFailure { logger.warn(it) { "refresh sweep failed" } }
        } else {
          logger.debug { "refresh-sweep lease held by another replica — skipping this tick" }
        }
        delay(intervalMs)
      }
    }
  }

  fun stop() {
    job?.cancel()
    job = null
    // Best-effort early release so a graceful shutdown hands the sweep over immediately instead
    // of the next leader waiting out the lease TTL.
    runCatching { leaseDao.release(LeaseDao.TOKEN_REFRESH_SWEEP, instanceId.value) }
  }

  /**
   * One sweep: the warm tier first, then preservation. Both hand their rows to the shared
   * [PodTokenProvider], which owns the issuer-pin + rotate + persist logic and serialises each
   * connection against concurrent on-demand tool refreshes (in-process mutex + cross-replica claim).
   */
  suspend fun refreshDueTokens() {
    warmDueTokens()
    preserveDueFamilies()
  }

  /**
   * Renew the access tokens of connections somebody has used lately, so their next call pays no
   * latency. `podTokenWarmIdleSeconds = 0` turns the tier off entirely, leaving preservation alone —
   * the smallest shape of this sweep, where every first call after an idle period rotates on demand.
   *
   * Budgeted like preservation, and for a blunter reason: a failed refresh moves neither the expiry
   * nor the use marker, so a pod that fails slowly stays warm-due for as long as somebody keeps
   * using it, and an unbounded loop would let it stretch the tick without limit. What this tier
   * drops is only ever *pre*-warming — the connection still gets its token from
   * [PodTokenProvider.validAccessToken] on the next call, one rotation later than it would have.
   * That is why the rows it does not reach need no attempt marker, where preservation's do: there
   * is no second path holding a refresh-token family open.
   */
  private suspend fun warmDueTokens() {
    val warmIdleSeconds = config.podTokenWarmIdleSeconds
    if (warmIdleSeconds <= 0) return
    val now = System.currentTimeMillis()
    val trigger = RefreshTrigger.Expiring(config.podTokenRefreshWindowSeconds)
    val due = tokenVaultDao.findExpiringBefore(
      cutoff = Date(now + config.podTokenRefreshWindowSeconds * 1000),
      usedSince = Date(now - warmIdleSeconds * 1000),
      limit = TIER_BATCH,
    )
    val deadline = now + tierBudgetMs
    for ((index, tokens) in due.withIndex()) {
      if (System.currentTimeMillis() >= deadline) {
        logger.info { "warm pass out of budget after $index of ${due.size} — the rest refreshes on demand" }
        return
      }
      refresh(tokens, trigger, tier = "warm")
    }
  }

  /**
   * Rotate connections whose refresh-token family is drifting toward its deadline, used or not.
   *
   * Bounded by time rather than by a count: every connection alive when this cadence ships comes due
   * within the same pass (today's 55-minute rotation leaves all their stamps inside one hour), and a
   * budget both keeps the warm tier responsive through that herd and lets the herd spread itself
   * out — a rotation moves the stamp to whenever it actually ran. Lateness here is cheap by
   * construction: the cadence sits two thirds of the deadline away from it.
   *
   * A budget alone would not be safe, though, and the unsafe version is quiet. A failed refresh
   * persists nothing, so a row that fails slowly keeps its rotation stamp and would sit at the head
   * of a stamp-ordered selection forever: one pod that accepts connections and then does not answer
   * costs four requests at up to the client's 30 s read timeout apiece, more than this whole budget,
   * every tick — and the rows behind it are never attempted at all, families expiring while every
   * pass completes and every signal at this level looks healthy. So each row is marked as attempted
   * **before** it is attempted, and [TokenVaultDao.findNotRotatedSince] orders by that mark: an
   * attempted row goes to the back, so no row is tried twice before every other due row has been
   * tried once.
   *
   * The budget is anchored where this tier *starts*, deliberately, not where the sweep did: the
   * warm tier runs first, and a warm pass that spent its own budget would otherwise hand this one a
   * deadline already in the past — zero rows attempted, no marker written, and the guarantee this
   * tier exists for quietly not held because the latency tier had a bad tick.
   */
  private suspend fun preserveDueFamilies() {
    val preserveSeconds = config.podTokenFamilyPreserveSeconds
    if (preserveSeconds <= 0) return
    val now = System.currentTimeMillis()
    val notRotatedSince = Date(now - preserveSeconds * 1000)
    val due = tokenVaultDao.findNotRotatedSince(notRotatedSince, limit = TIER_BATCH)
    // A row whose ciphertext will not decrypt needs the same mark as one this pass attempts, and for
    // the same reason: nothing else can move it out of the head of the queue — it carries no mark,
    // and its rotation stamp cannot move because nothing can rotate it. Unmarked, a batch of them
    // would mask every row behind them for good. The warning is `TokenVaultDao`'s.
    due.unreadable.forEach { tokenVaultDao.markRefreshAttempted(it, Date()) }
    val trigger = RefreshTrigger.Preserving(notRotatedSince)
    val deadline = now + tierBudgetMs
    for ((index, tokens) in due.rows.withIndex()) {
      if (System.currentTimeMillis() >= deadline) {
        // Said out loud: a pass that stops early otherwise reads, from every signal at this level,
        // exactly like one that covered everything.
        logger.info { "preservation pass out of budget after $index of ${due.rows.size} — the rest is next tick's" }
        return
      }
      // Before, not after: a row that wedges this pass must still leave the selection.
      tokenVaultDao.markRefreshAttempted(PodKey(tokens.user, tokens.profile, tokens.pod), Date())
      refresh(tokens, trigger, tier = "preserve")
    }
  }

  /** Caught per token: one throttled/blocked pod must not starve the other due tokens of this sweep. */
  private suspend fun refresh(tokens: PodTokens, trigger: RefreshTrigger, tier: String) {
    runCatching { podTokenProvider.refreshIfDue(tokens, trigger) }
      .onFailure {
        if (it is CancellationException) throw it // shutdown mid-sweep: stop, don't log every remaining token
        logger.warn(it) { "$tier sweep refresh failed for pod '${tokens.pod}'" }
      }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    /**
     * How many rows one tier reads per tick. Matched to what the time budget could spend anyway at
     * the ~150 ms a healthy rotation takes, so it does not ration work the budget would have
     * allowed; what it bounds is how much one tick decrypts and holds at once, which the ordering
     * makes safe to bound — the rows it leaves behind are the back of the queue, not rows that
     * would be skipped forever.
     */
    private const val TIER_BATCH = 500
  }
}
