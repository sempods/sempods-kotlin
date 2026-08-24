package org.sempods.commons.ratelimit

/**
 * A hand-cranked millisecond clock, in the shape [TokenBucketRateLimiter] takes one: a `() -> Long`
 * whose value a test moves itself.
 *
 * Shared rather than declared per suite because the limiter is used from three modules now, and a
 * token bucket is exactly the kind of thing whose arithmetic has to be exercised without waiting
 * for a wall clock — every one of those suites needs the same four lines.
 */
class FakeClock(var nowMs: Long = 0) : () -> Long {
  override fun invoke(): Long = nowMs

  /** Moves the clock forward, which is the only direction a test usually wants. */
  fun advance(millis: Long) {
    nowMs += millis
  }
}
