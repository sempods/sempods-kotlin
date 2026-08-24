package org.sempods.mcp.pods

import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.TokenVaultDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Why a row is up for refresh. The two entries answer to different clocks, and the difference is
 * load-bearing rather than cosmetic: the re-check *under the claim* is what stops a competitor's
 * just-finished rotation from being rotated a second time, and a second rotation trips the pod's
 * refresh-token family-reuse detection and breaks the connection. So a preservation refresh may not
 * be dressed up as an [Expiring] one with a very wide window — under that predicate a freshly
 * rotated row still reads as due, and the guard would pass exactly when it must not.
 */
sealed interface RefreshTrigger {
  /**
   * The access token expires within [withinSeconds]. The on-demand path's own clock (a small skew),
   * and the sweep's warm tier (the configured window).
   */
  data class Expiring(val withinSeconds: Long) : RefreshTrigger

  /**
   * The row has not rotated since [notRotatedSince] — the sweep's preservation tier. Deliberately
   * indifferent to the access token: what it is holding open is the refresh-token family and the
   * pod-side DCR registration, both of which any single rotation resets in full. A row with an
   * unknown expiry is therefore preserved here, where [Expiring] can never select it.
   */
  data class Preserving(val notRotatedSince: Date) : RefreshTrigger
}

/**
 * Single source of truth for *"give me a usable pod access token for `(user, profile, pod)`"*.
 * Shared by the synchronous read tools (M3) and the background [TokenRefreshScheduler] sweep (M2),
 * so the discover → **issuer-pin** → rotate → persist logic lives in exactly one place.
 *
 * Refreshes for the same connection are serialised at two levels: pods rotate refresh tokens with
 * family-reuse detection, so ANY double-refresh (sweep + tool call, or two replicas) would
 * invalidate the family and break the connection.
 *  - **In-process:** a per-key mutex — coroutines in the same JVM wait for each other and re-read
 *    the vault row inside the lock, so a token another holder just rotated is reused, not
 *    re-refreshed.
 *  - **Cross-replica (M6.3):** an expiring claim on the vault row ([TokenVaultDao.tryClaimRefresh],
 *    keyed by this replica's [instanceId]) — exactly one replica refreshes; the sweep skips a
 *    claimed token (the winner is refreshing it), an on-demand caller briefly polls for the
 *    winner's result and falls back to the optimistic current token.
 */
class PodTokenProvider(
  private val tokenVaultDao: TokenVaultDao,
  private val connectionRegistryDao: ConnectionRegistryDao,
  private val podOAuthClient: PodOAuthClient,
  private val auditLog: AuditLog,
  /** How long before the recorded expiry a token is treated as due for a proactive refresh. */
  private val expirySkewSeconds: Long = 30,
  /** This replica's identity for cross-replica refresh claims; a crashed holder's claim expires. */
  private val instanceId: String = "local-" + UUID.randomUUID().toString().take(8),
  /**
   * How long a refresh claim holds. Worst-case refresh ≈ 3 sequential HTTP calls × the pod
   * client's 10 s request timeout ≈ 30 s; 2× headroom. A crashed holder delays that one token's
   * refresh by at most this — invisible inside the (default 300 s) refresh window.
   */
  private val claimTtlMs: Long = 60_000,
) {

  // One Mutex per (user, profile, pod) ever touched, with growth control (M6.4): past MAX_LOCKS
  // entries, currently-unlocked mutexes are swept — CAS-gated to at most once per
  // LOCK_SWEEP_INTERVAL_MILLIS, so the O(n) sweep never sits on the per-request hot path.
  // Evicting an unlocked mutex is SAFE: since M6.3 the correctness primitive against double-refresh
  // is the cross-replica vault claim (tryClaimRefresh + dueness re-check under the claim); this
  // in-process mutex only reduces claim contention. Two same-JVM coroutines that briefly hold
  // different Mutex instances for one key behave exactly like two replicas — one wins the claim,
  // the loser skips/polls. See docs/multi-tenancy-review.md.
  private val locks = ConcurrentHashMap<PodKey, Mutex>()
  private val lastLockSweepAt = AtomicLong(0)
  internal val lockCount: Int get() = locks.size

  internal fun lockFor(key: PodKey): Mutex {
    if (locks.size > MAX_LOCKS) sweepLocksIfDue()
    return locks.computeIfAbsent(key) { Mutex() }
  }

  private fun sweepLocksIfDue() {
    val now = System.currentTimeMillis()
    val last = lastLockSweepAt.get()
    if (now - last < LOCK_SWEEP_INTERVAL_MILLIS || !lastLockSweepAt.compareAndSet(last, now)) return
    locks.entries.removeIf { !it.value.isLocked }
  }

  /** The on-demand path's own dueness: valid *now*, not the wider window the sweep keeps warm on. */
  private val onDemand get() = RefreshTrigger.Expiring(expirySkewSeconds)

  /**
   * Record that this connection was just used, on the one path a pod read or write goes through.
   * The sweep's warm tier selects on that marker, so this is where "used" is defined for the whole
   * service: a call that obtained a token for a pod. `list_pods`, `authorize` and the dashboard
   * never reach here — they answer from local state and touch no pod — and neither does the sweep,
   * which enters at [refreshIfDue], so warm-keeping cannot mark itself as use.
   *
   * Throttled against the row already in hand, so a burst of tool calls costs one write per
   * connection per [TOUCH_GRANULARITY_MS] and no extra read. Nothing is marked when the caller
   * leaves empty-handed: a null return never reached the pod.
   */
  private fun String?.alsoMarkUsed(key: PodKey, row: PodTokens): String? = also {
    if (it == null) return@also
    val now = System.currentTimeMillis()
    val last = row.lastUsedAt?.time
    if (last != null && now - last < TOUCH_GRANULARITY_MS) return@also
    runCatching { tokenVaultDao.touchLastUsed(key, Date(now), ifOlderThan = Date(now - TOUCH_GRANULARITY_MS)) }
      // Best-effort, like the audit trail: a connection that misses a mark is warm-kept a little
      // less, which is not worth failing a tool call over.
      .onFailure { e -> logger.warn(e) { "could not mark $key as used" } }
  }

  /**
   * A pod access token that stays valid past [expirySkewSeconds] when the row is refreshable and has a known expiry,
   * refreshing on demand when the recorded expiry is near. Returns null when the pod is not connected, the connection's
   * grant is one the pod already declared finished, the row is expired and un-refreshable, or a needed refresh fails —
   * the caller surfaces all of them as "reconnect this pod". A token with an **unknown** expiry is
   * returned as-is (used optimistically): if it is in fact stale the pod answers 401 and the tool
   * surfaces that, which is cheaper than refresh-churning every call for a non-conformant pod.
   *
   * The on-demand path only needs the token valid *now*, so it refreshes within the small
   * [expirySkewSeconds] — not the wider proactive window the background sweep uses ([refreshIfDue]).
   */
  suspend fun validAccessToken(key: PodKey): String? {
    val current = tokenVaultDao.find(key) ?: return null
    if (!isDue(current, onDemand)) return usableOrNull(current).alsoMarkUsed(key, current)
    return lockFor(key).withLock {
      // Re-read inside the lock: another holder (sweep or a concurrent tool call) may have rotated
      // the token while we waited, in which case we reuse theirs instead of refreshing again.
      val latest = tokenVaultDao.find(key) ?: return@withLock null
      when {
        !isDue(latest, onDemand) -> usableOrNull(latest).alsoMarkUsed(key, latest)
        // Asked inside the lock, for both halves of the wait: a token the holder ahead of us just
        // rotated is reused by the arm above without ever reading the connection, and a grant that
        // same holder just found dead is seen by this one. Null is what the doomed refresh returned
        // anyway — the caller still says "reconnect this pod", it just stops costing a claim and
        // three HTTP round trips per call.
        hasDeadGrant(key) -> null
        claimRefresh(key) -> refreshClaimed(key, onDemand)?.let { usableOrNull(it).alsoMarkUsed(key, it) }
        // Another replica holds the claim and is refreshing right now — briefly poll for its
        // result instead of double-refreshing (which would trip refresh-token-family reuse).
        else -> awaitOtherReplica(key)
      }
    }
  }

  /**
   * Poll the vault for the claim-holding replica's result: once the row's expiry moves out of the
   * due window the other replica has persisted its refresh and we reuse that token. On timeout
   * (the holder is slow, crashed, or found the grant dead) fall back to the row's current token
   * used optimistically — it may still have up to [expirySkewSeconds] of life; a hard-expired one
   * (or a row deleted by a disconnect mid-poll) returns null and the caller surfaces "reconnect
   * this pod", same as a failed refresh.
   */
  private suspend fun awaitOtherReplica(key: PodKey): String? {
    logger.debug { "refresh claim for $key held by another replica — awaiting its result" }
    repeat(CLAIM_POLL_ATTEMPTS) {
      delay(CLAIM_POLL_INTERVAL_MS)
      val row = tokenVaultDao.find(key) ?: return null
      if (!isDue(row, onDemand)) return usableOrNull(row).alsoMarkUsed(key, row)
    }
    // The holder may have found the grant dead rather than merely being slow — and that outcome
    // persists nothing, so the row we polled never moved and every iteration above saw it as still
    // due. Without this the fallback would hand back a token from a grant the pod has just declared
    // finished, for the rest of the skew window, while the claim-winning path answers null: one
    // read, on the timeout path only, so the answer does not depend on which replica found out.
    if (hasDeadGrant(key)) return null
    return tokenVaultDao.find(key)?.let { usableOrNull(it).alsoMarkUsed(key, it) }
  }

  /**
   * A not-due token to hand back, or null. A token with an **unknown** expiry is returned (used
   * optimistically — the pod 401s if it turns out stale). But a token with a **known, already-past**
   * expiry that landed here can only be the un-refreshable case (a refreshable expired token is
   * [isDue] → refreshed instead): handing it out would just 401 forever, so return null and let the
   * caller surface "reconnect this pod".
   */
  private fun usableOrNull(tokens: PodTokens): String? = if (isExpired(tokens)) null else tokens.accessToken

  private fun isExpired(tokens: PodTokens): Boolean {
    val expiresAt = tokens.accessTokenExpiresAt ?: return false
    return expiresAt.time <= System.currentTimeMillis()
  }

  /**
   * Background-sweep entry: refresh a row the scheduler selected, for the reason [trigger] names —
   * [RefreshTrigger.Expiring] for the warm tier (a recently-used connection whose access token is
   * inside the configured window, so the next call pays no latency), [RefreshTrigger.Preserving] for
   * the preservation tier (any connection whose refresh-token family is drifting toward its
   * ninety-day deadline). Goes through the same per-key lock and re-check under the same trigger, so
   * it never double-refreshes a token a tool call, another replica or a prior sweep just rotated.
   */
  suspend fun refreshIfDue(tokens: PodTokens, trigger: RefreshTrigger) {
    val key = PodKey(tokens.user, tokens.profile, tokens.pod)
    lockFor(key).withLock {
      val latest = tokenVaultDao.find(key) ?: return@withLock
      if (!isDue(latest, trigger)) return@withLock
      // A connection the pod has declared finished never leaves the sweep's selection: neither its
      // expiry nor its rotation stamp ever moves, so the selection hands it back on every tick.
      if (hasDeadGrant(key)) return@withLock
      if (!claimRefresh(key)) {
        // Another replica is refreshing this token; the next sweep re-checks (≤ interval later,
        // well inside the refresh window) — nothing to wait for here.
        logger.debug { "refresh claim for $key held by another replica — sweep skips it" }
        return@withLock
      }
      refreshClaimed(key, trigger)
    }
  }

  private fun claimRefresh(key: PodKey): Boolean =
    tokenVaultDao.tryClaimRefresh(key, instanceId, Date(System.currentTimeMillis() + claimTtlMs))

  /**
   * True when the pod has already declared this connection's grant finished — the RFC 6749 §5.2
   * `invalid_grant` that [refreshLocked] records as `PodConnection.deadGrantSince`. Nothing but a
   * reconnect clears it (`/_system/ui` writes a fresh registry row), so every further refresh earns
   * the same refusal: a metadata discovery and a token POST per tick, for as long as the row exists.
   *
   * Asked at both entries **ahead of** [claimRefresh], not inside [refreshLocked] where the row is
   * already loaded: by then the claim has been taken and has to be released again, so a connection
   * that is dead on every replica would still be serialised across them — two writes a tick to the
   * collection the sweep is scanning, around a refusal that needs no coordination at all.
   *
   * A **missing** registry row is deliberately not "dead": that is the other fault — a vault row
   * whose connection row was lost — and [refreshLocked] still names it in its own warning. Folding
   * the two together here would retire that diagnostic silently.
   */
  private fun hasDeadGrant(key: PodKey): Boolean {
    val since = connectionRegistryDao.find(key)?.deadGrantSince ?: return false
    logger.debug { "connection for $key was declared dead at $since — skipping refresh until a reconnect" }
    return true
  }

  /**
   * Must be called holding the claim (and [lockFor]). Re-reads and re-checks dueness UNDER the
   * claim: a competing replica's refresh only releases its claim by persisting (the replace drops
   * the claim fields), so a refresh that completed between our due-check and our claim is visible
   * in this re-read — without it we would refresh a second time and trip the pod's
   * refresh-token-family reuse detection. A successful refresh persists via
   * [TokenVaultDao.replaceIfClaimedBy] (conditional on the claim still being ours, so a mid-refresh
   * re-connect wins; the replace doubles as the release); on the no-op/null/exception paths the
   * claim is released explicitly so the next holder need not wait out [claimTtlMs].
   */
  private suspend fun refreshClaimed(key: PodKey, trigger: RefreshTrigger): PodTokens? {
    var persisted = false
    try {
      val latest = tokenVaultDao.find(key) ?: return null
      if (!isDue(latest, trigger)) return latest // another replica already refreshed it
      val refreshed = refreshLocked(latest)
      persisted = refreshed != null
      return refreshed
    } finally {
      if (!persisted) tokenVaultDao.releaseRefreshClaim(key, instanceId)
    }
  }

  /**
   * Whether a row is due for the reason [trigger] names. A row without a refresh token is never due
   * under either — there is nothing to rotate with.
   *
   * Under [RefreshTrigger.Expiring] an **unknown** expiry is deliberately not due: tokens are used
   * optimistically (see [validAccessToken]), so the vault's "null expiry" rows are not churned
   * through a refresh on every call. [RefreshTrigger.Preserving] does select them, which is what
   * they always needed and never got — their family has a deadline whether or not their access
   * token declares one.
   */
  private fun isDue(tokens: PodTokens, trigger: RefreshTrigger): Boolean {
    if (tokens.refreshToken == null) return false
    return when (trigger) {
      is RefreshTrigger.Expiring -> {
        val expiresAt = tokens.accessTokenExpiresAt ?: return false
        expiresAt.time <= System.currentTimeMillis() + trigger.withinSeconds * 1000
      }
      is RefreshTrigger.Preserving -> tokens.updatedAt.before(trigger.notRotatedSince)
    }
  }

  /** Must be called while holding [lockFor]. Discovers, issuer-pins, refreshes, persists; returns
   *  the rotated row, or null if there is no connection row, the issuer no longer matches, or the
   *  pod refuses the refresh. */
  private suspend fun refreshLocked(tokens: PodTokens): PodTokens? {
    val key = PodKey(tokens.user, tokens.profile, tokens.pod)
    val refreshToken = tokens.refreshToken ?: return null
    val connection = connectionRegistryDao.find(key) ?: run {
      logger.warn { "no connection registry row for $key — skipping refresh" }
      return null
    }
    return runCatching {
      val metadata = podOAuthClient.discoverMetadata(tokens.pod)
      // Pin to the issuer chosen at connect time: if the pod's metadata now points at a different
      // authorization server (DNS/domain takeover, misconfig), refuse to post the stored refresh
      // token to that new token endpoint — otherwise a metadata change could exfiltrate and rotate
      // the user's pod refresh token.
      if (metadata.issuer != connection.issuer) {
        logger.warn {
          "issuer mismatch for $key (connected='${connection.issuer}', discovered='${metadata.issuer}') — skipping refresh"
        }
        auditLog.podTokenRefreshed(key, ok = false, detail = "issuer_mismatch")
        return@runCatching null
      }
      val refreshed = podOAuthClient.refresh(metadata, refreshToken, connection.podClientId)

      // Re-verify the identity on refresh. Three outcomes, three responses:
      //  - VerificationFailed: the refreshed token IS a JWT but its signature did not verify against
      //    the pod's advertised (and fetched) JWKS — a positive tamper/misconfig signal. Refuse: never
      //    persist a token our own verification rejected.
      //  - Readable + subject drifted from the recorded podSubject: refuse — a pod could otherwise hand
      //    back a token for another identity (session change, takeover) while list_pods / read+write
      //    annotations keep reporting the old one. The stale refresh token then surfaces as "reconnect
      //    this pod" rather than silently acting as someone else.
      //  - Unreadable (opaque/sub-less token, or a transient JWKS-fetch blip): NOT drift — refusing
      //    would discard the freshly rotated refresh token and brick a healthy connection over a
      //    non-identity hiccup. Keep the token (no worse than the pre-identity behaviour, which stored
      //    refreshes unconditionally) and leave the recorded identity untouched.
      // A legacy row with no recorded podSubject has nothing to protect and is backfilled below.
      val outcome = podOAuthClient.verifyAccessTokenSubject(metadata, refreshed.accessToken)
      if (outcome is PodOAuthClient.SubjectOutcome.VerificationFailed) {
        logger.warn { "refreshed pod token for $key failed JWKS signature verification — refusing" }
        auditLog.podTokenRefreshed(key, ok = false, detail = "verification_failed")
        return@runCatching null
      }
      val subject = (outcome as? PodOAuthClient.SubjectOutcome.Readable)?.subject
      if (subject != null && connection.podSubject != null && subject.webId != connection.podSubject) {
        logger.warn {
          "identity drift on refresh for $key (recorded='${connection.podSubject}', refreshed='${subject.webId}') — refusing"
        }
        auditLog.podTokenRefreshed(key, ok = false, detail = "identity_drift")
        return@runCatching null
      }

      val now = Date()
      val updated = tokens.copy(
        accessToken = refreshed.accessToken,
        refreshToken = refreshed.refreshToken ?: refreshToken,
        accessTokenExpiresAt = refreshed.expiresInSeconds?.let { Date(now.time + it * 1000) },
        updatedAt = now,
      )
      if (!tokenVaultDao.replaceIfClaimedBy(updated, instanceId)) {
        // A concurrent re-connect replaced the row (clearing our claim) — or a disconnect deleted
        // it — while this refresh was in flight. The re-connect minted a brand-new token family
        // that supersedes our rotation of the old one: discard ours and hand back whatever the
        // vault holds now (null after a disconnect → the caller surfaces "reconnect this pod").
        logger.info {
          "pod token row for $key changed mid-refresh (re-connect/disconnect) — discarding the stale rotation"
        }
        return@runCatching tokenVaultDao.find(key)
      }
      // Keep the recorded identity accurate: backfill a legacy null podSubject, or reflect a pod that
      // has since added a JWKS (unverified → verified). No write when the subject is unreadable or
      // nothing changed.
      if (subject != null && (connection.podSubject != subject.webId || connection.subjectVerified != subject.verified)) {
        connectionRegistryDao.upsert(
          connection.copy(podSubject = subject.webId, subjectVerified = subject.verified, updatedAt = now),
        )
      }
      logger.info { "refreshed pod token for $key" }
      auditLog.podTokenRefreshed(key, ok = true)
      updated
    }.getOrElse { e ->
      // Never swallow cancellation — let structured concurrency tear the request down.
      if (e is CancellationException) throw e
      // A failure that is worth retrying is not a dead refresh token — propagate so the caller
      // reports pod_error instead of the misleading "reconnect this pod". Deliberately NOT
      // audited: a throttled pod would flood the trail, and the failure surfaces on the (audited)
      // tool call as pod_error.
      if (e.isRetryablePodFailure()) throw e
      // A revoked/expired/reused refresh token will keep failing. The row stays — it carries the
      // pod URL the person needs in order to reconnect — but it is marked, so the dashboard says
      // "reconnect needed" instead of showing the pod as healthy while every tool call quietly
      // returns no token.
      //
      // The cause chain, not a top-level `as?`: `isRetryablePodFailure` above walks it for the
      // same reason, because the engine wraps what it throws. Marking only the RFC 6749 §5.2 code
      // is deliberate — anything else is an attempt that failed, not a grant that ended.
      if (e.isDeadPodGrant()) {
        // Logged, and the compare-and-set result with it: this is the one line that says a grant
        // *ended* rather than an attempt failing, and since the short-circuit at both entries it is
        // written once per death instead of once per tick. `false` means a reconnect landed while
        // this refresh was in flight and won — that connection is live, not dead.
        val marked = connectionRegistryDao.markDeadGrant(key, at = Date(), ifUpdatedAt = connection.updatedAt)
        logger.warn {
          if (marked) "pod declared the grant for $key finished — marked dead; no further refresh until a reconnect"
          else "pod declared the grant for $key finished, but the connection moved on mid-refresh — not marked"
        }
      }
      logger.warn(e) { "pod token refresh failed for $key" }
      // One audit detail for both, because `PodTokenProviderTest` pins the string and splitting it
      // would turn a mechanical change into a change to the audit trail.
      auditLog.podTokenRefreshed(key, ok = false, detail = "refresh_failed")
      null
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    // On-demand poll budget while another replica refreshes: 20 × 250 ms = 5 s, bounded so a
    // wedged remote refresh cannot hang a tool call.
    private const val CLAIM_POLL_ATTEMPTS = 20
    private const val CLAIM_POLL_INTERVAL_MS = 250L

    // How coarse the "last used" marker is. The warm window it feeds is measured in hours, so a
    // minute of imprecision buys the hot path one write per connection per minute instead of one
    // per tool call — and a read fan-out is one call per pod.
    private const val TOUCH_GRANULARITY_MS = 60_000L

    // Growth control for the per-key mutex map (M6.4) — same shape as TokenBucketRateLimiter.
    private const val MAX_LOCKS = 4096
    private const val LOCK_SWEEP_INTERVAL_MILLIS = 60_000L
  }
}
