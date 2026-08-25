package org.sempods.commons.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A continuous-refill token bucket per opaque string key.
 *
 * **The key is the caller's decision, and this class never inspects it.** What a bucket stands for
 * — a pod, a person, a client behind one address — is what the caller composes and documents; here
 * it is a map key and nothing more. [tryAcquire] rejects immediately rather than queuing, so a
 * throttled caller surfaces an error instead of stalling on a lock it cannot see.
 *
 * [permitsPerMinute] `<= 0` disables the limiter entirely, which is what lets a deployment turn it
 * off without the call sites growing a branch.
 *
 * **Rate and burst are separate, and conflating them is a trap.** [burstCapacity] is how much an
 * idle key is admitted at once; [permitsPerMinute] is how fast that is earned back. A single value
 * for both forces one number to answer two questions — "how big a legitimate spike do I absorb" and
 * "how fast may a caller keep going" — and the spike always wins, because it is the larger. The
 * result is a limit that never fires against a steady stream: a bucket refilling 300 a minute is
 * never emptied by a caller asking 78 times a minute, however long it keeps going. Choose the rate
 * below the sustained traffic that is not wanted, and the capacity above the burst that is.
 *
 * **The key space may be a caller's to choose, so the map is capped — by eviction, never by
 * refusal.** Where a key contains anything the caller supplies — a client id on an unauthenticated
 * endpoint, say — a caller that varies it never reuses a bucket, and every request allocates one.
 * An idle sweep does not answer that on its own: nothing is idle yet.
 *
 * So [maxKeys] is enforced when a new key arrives — under a lock, since a size check followed by an
 * insert bounds nothing once threads arrive together — and by making room rather than by turning
 * the caller away. **Refusing would be the worse failure**: the map is shared, so a caller able to keep it
 * full would be reserving it — every *other* key seen for the first time would be refused, which
 * denies the endpoint to everyone new while leaving the hoarder's own buckets served. Evicting
 * instead costs the evicted key its accumulated budget and nothing else; it is answered on the next
 * request as if it were new, which is what it now is.
 *
 * Eviction has three steps, cheapest first: the idle sweep ([IDLE_EVICT_MILLIS], at most once per
 * [SWEEP_INTERVAL_MILLIS] and CAS-gated, so the O(n) pass stays off the hot path); then, while
 * full, the same pass at [PRESSURE_IDLE_EVICT_MILLIS] — which separates the two populations under a
 * flood, since a real caller touches its bucket repeatedly and a minted key exactly once; and if
 * neither freed anything, the least recently touched entry and its ties.
 *
 * **In-memory per process, deliberately.** A shared counter would put a database on the per-request
 * hot path of the very requests a limiter exists to make cheap. The effective budget therefore
 * scales with the number of replicas, which is a property a caller running more than one has to
 * state in its own documentation.
 *
 * **[clock] measures elapsed time and nothing else**, which is why it defaults to a monotonic
 * source rather than the wall clock. Every use of it here is a difference — how much a bucket has
 * earned, how long a key has been idle, when the sweep last ran — and none is a point in time
 * anyone reports. A wall clock steps sideways when NTP or a hypervisor corrects it, and a limiter
 * that reads one is wrong in both directions on the way through. Injectable so the arithmetic can
 * be exercised without waiting, and a caller may hand in a wall clock if it has a reason
 * (`sempods-mcp`'s audit sampler shares the clock that stamps its rows) — the watermark below is
 * guarded either way.
 */
class TokenBucketRateLimiter(
  private val permitsPerMinute: Int,
  private val clock: () -> Long = MONOTONIC_MILLIS,
  private val burstCapacity: Int = permitsPerMinute,
  private val maxKeys: Int = MAX_KEYS,
) {

  init {
    // Only meaningful while the limiter is on; a disabled one never reads either value. A capacity
    // of zero beside a positive rate would refuse *everything*, which is the one misconfiguration
    // that must not be reachable by construction.
    require(permitsPerMinute <= 0 || burstCapacity >= 1) {
      "burstCapacity must be at least 1 when permitsPerMinute is positive, got $burstCapacity"
    }
  }

  private val buckets = ConcurrentHashMap<String, Bucket>()
  private val lastSweepAt = AtomicLong(0)

  /**
   * Held while a key is admitted for the first time, so that reading the size, making room and
   * inserting are one step rather than three.
   *
   * Without it the ceiling is advisory: several threads read the same size, each frees one slot and
   * each inserts, and the map ends up over [maxKeys] by however many were in flight. It is
   * contended only by first-time keys — an established caller never reaches this path — so the
   * request that pays for it is the one that would otherwise have grown the map.
   */
  private val admission = Any()

  fun tryAcquire(key: String): Boolean {
    if (permitsPerMinute <= 0) return true

    // A key already known is answered without consulting the cap: whatever pressure the map is
    // under, an established caller keeps the budget it has been spending.
    buckets[key]?.let { return it.tryTake() }

    // A key seen for the first time is where an unbounded map would come from, so it is the one
    // place the size is enforced rather than merely observed — and enforced under a lock, because
    // a check followed by an insert is not a capacity check at all when threads arrive together.
    val bucket = synchronized(admission) {
      // Re-read inside the lock: another thread may have admitted this very key while we waited,
      // and inserting a second bucket for it would hand the caller two budgets.
      buckets[key] ?: run {
        if (buckets.size >= maxKeys) makeRoom()
        Bucket(tokens = burstCapacity.toDouble(), lastRefillAt = clock())
          .also { buckets[key] = it }
      }
    }
    // Outside the lock: the bucket does its own synchronisation, and taking a permit must not
    // serialise behind whoever is currently making room.
    return bucket.tryTake()
  }

  /** How many keys are tracked right now. Exists for the test that pins the ceiling. */
  internal fun trackedKeyCount(): Int = buckets.size

  private fun sweepIfDue() {
    val now = clock()
    val last = lastSweepAt.get()
    if (now - last < SWEEP_INTERVAL_MILLIS || !lastSweepAt.compareAndSet(last, now)) return
    // Tighter while the map is full, for the reason the class KDoc gives: one touch is the
    // signature of a minted key, and repeated touches are the signature of a caller.
    val idleFor = if (buckets.size >= maxKeys) PRESSURE_IDLE_EVICT_MILLIS else IDLE_EVICT_MILLIS
    evictOlderThan(now - idleFor)
  }

  /**
   * Frees at least one slot, so that a first-time key is never turned away for want of room.
   *
   * The idle passes are the cheap ones and handle the case this exists for — a flood of keys each
   * touched once. The last resort runs only when every entry has been touched recently, and evicts
   * the least recently touched of them: whoever is holding the map is by definition holding most of
   * those entries, so the eviction lands on them far more often than on anyone else.
   */
  private fun makeRoom() {
    sweepIfDue()
    if (buckets.size < maxKeys) return
    val now = clock()
    evictOlderThan(now - PRESSURE_IDLE_EVICT_MILLIS)
    if (buckets.size < maxKeys) return
    // Exactly one victim, not "the oldest and every tie": the clock is milliseconds and a full map
    // of equally warm entries has plenty of ties, so evicting them together would empty the map and
    // hand every one of those callers a fresh burst. One slot is all that is needed.
    val victim = buckets.entries.minByOrNull { it.value.lastTouchedAt() } ?: return
    buckets.remove(victim.key, victim.value)
  }

  private fun evictOlderThan(cutoff: Long) {
    buckets.entries.removeIf { it.value.lastTouchedAt() < cutoff }
  }

  private inner class Bucket(private var tokens: Double, private var lastRefillAt: Long) {
    @Synchronized fun tryTake(): Boolean {
      val now = clock()
      // Clamp to >= 0: a clock step backwards would otherwise DRAIN tokens (tokens + negative
      // refill) and over-throttle a key that saw no traffic at all.
      val elapsedMinutes = ((now - lastRefillAt).coerceAtLeast(0)) / 60_000.0
      tokens = minOf(burstCapacity.toDouble(), tokens + elapsedMinutes * permitsPerMinute)
      // The watermark only ever moves forward, and that is the other half of the same problem.
      // Letting it follow a clock backwards would re-credit the interval it had already paid for:
      // after a six-second rollback a 60/minute bucket earns those six seconds a second time on the
      // way back, and a correction the size of an hour would hand back a whole burst. Refusing to
      // move it means a rolled-back clock stops earning until it catches up, which is the direction
      // to be wrong in.
      lastRefillAt = maxOf(lastRefillAt, now)
      if (tokens < 1.0) return false
      tokens -= 1.0
      return true
    }

    @Synchronized fun lastTouchedAt(): Long = lastRefillAt
  }

  companion object {
    /**
     * How many distinct keys are tracked at once. A hard ceiling rather than a sweep threshold —
     * see the class KDoc for why a caller-chosen key space makes that the difference between a
     * bounded map and one that grows with request volume.
     */
    const val MAX_KEYS = 4096

    private const val IDLE_EVICT_MILLIS = 10 * 60_000L

    /**
     * The cutoff used while the map is at [MAX_KEYS] — one refill window rather than ten, so a
     * flood of single-use keys is released quickly and a legitimate caller that has not been seen
     * before waits at most a sweep interval to get in.
     */
    private const val PRESSURE_IDLE_EVICT_MILLIS = 60_000L

    private const val SWEEP_INTERVAL_MILLIS = 60_000L

    /**
     * Milliseconds from a source that cannot step sideways. `nanoTime`'s zero is arbitrary, which
     * costs nothing here: every reading is subtracted from another.
     */
    private val MONOTONIC_MILLIS: () -> Long = { System.nanoTime() / 1_000_000 }

    /** The same source, for a caller that constructs its own limiter and passes the clock down. */
    fun monotonicMillis(): () -> Long = MONOTONIC_MILLIS
  }
}
