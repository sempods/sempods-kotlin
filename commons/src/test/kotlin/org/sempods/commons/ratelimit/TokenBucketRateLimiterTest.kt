package org.sempods.commons.ratelimit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketRateLimiterTest {

  @Test fun `a non-positive budget disables the limiter`() {
    val limiter = TokenBucketRateLimiter(0)
    repeat(1000) { assertTrue(limiter.tryAcquire("pod")) }
  }

  @Test fun `the bucket allows up to the per-minute budget then rejects`() {
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(3, clock)
    assertTrue(limiter.tryAcquire("pod"))
    assertTrue(limiter.tryAcquire("pod"))
    assertTrue(limiter.tryAcquire("pod"))
    assertFalse(limiter.tryAcquire("pod"))
  }

  @Test fun `tokens refill continuously over time`() {
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(60, clock) // one token per second
    repeat(60) { assertTrue(limiter.tryAcquire("pod")) }
    assertFalse(limiter.tryAcquire("pod"))
    clock.advance(2_000) // two seconds → two tokens back
    assertTrue(limiter.tryAcquire("pod"))
    assertTrue(limiter.tryAcquire("pod"))
    assertFalse(limiter.tryAcquire("pod"))
  }

  @Test fun `a clock step backwards does not hand back an interval already earned`() {
    // The other half of the rollback problem, and the one that weakens rather than tightens: a
    // watermark that follows the clock down re-credits the span it had already paid for. Six
    // seconds of a 60/minute bucket here; a correction the size of an hour would be a whole burst.
    val clock = FakeClock(nowMs = 10_000)
    val limiter = TokenBucketRateLimiter(60, clock, burstCapacity = 60)
    repeat(60) { assertTrue(limiter.tryAcquire("pod")) }   // spends the burst at t=10s
    assertFalse(limiter.tryAcquire("pod"))

    clock.nowMs = 4_000                                    // NTP steps the wall clock back six seconds
    assertFalse(limiter.tryAcquire("pod"))

    clock.nowMs = 10_000                                   // and the clock catches up to where it was
    assertFalse(limiter.tryAcquire("pod"), "the bucket earned the same six seconds twice")

    clock.advance(1_000)                                   // a second past the watermark earns one
    assertTrue(limiter.tryAcquire("pod"))
    assertFalse(limiter.tryAcquire("pod"))
  }

  @Test fun `a clock step backwards does not drain the bucket`() {
    val clock = FakeClock(nowMs = 10_000)
    val limiter = TokenBucketRateLimiter(5, clock)
    assertTrue(limiter.tryAcquire("pod")) // consumes 1, 4 left
    clock.nowMs = 4_000 // NTP steps the wall clock back 6s
    // Without the clamp the negative elapsed would drain the bucket well below zero and reject.
    assertTrue(limiter.tryAcquire("pod"))
    assertTrue(limiter.tryAcquire("pod"))
    assertTrue(limiter.tryAcquire("pod"))
  }

  @Test fun `the burst is what an idle key spends, and the rate is what it earns back`() {
    // The distinction a single number cannot express: absorb a spike of 100 without ever letting a
    // caller sustain more than 10 a minute.
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(10, clock, burstCapacity = 100)
    repeat(100) { assertTrue(limiter.tryAcquire("svc")) }
    assertFalse(limiter.tryAcquire("svc"))

    clock.advance(60_000) // one minute earns ten back, not a hundred
    repeat(10) { assertTrue(limiter.tryAcquire("svc")) }
    assertFalse(limiter.tryAcquire("svc"))
  }

  @Test fun `a bucket never refills past its burst, however long it idles`() {
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(60, clock, burstCapacity = 5)
    clock.advance(24 * 60 * 60_000) // a day of idleness earns 86,400 permits, and caps at five
    repeat(5) { assertTrue(limiter.tryAcquire("svc")) }
    assertFalse(limiter.tryAcquire("svc"))
  }

  @Test fun `a steady caller under the rate is never refused`() {
    // The failure this pair exists to prevent: a rate above the traffic refuses nothing at all.
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(20, clock, burstCapacity = 300)
    repeat(300) { assertTrue(limiter.tryAcquire("loop")) } // spends the burst
    repeat(60) {
      clock.advance(6_000) // ten a minute, comfortably under the rate
      assertTrue(limiter.tryAcquire("loop"))
    }
  }

  @Test fun `the burst defaults to the rate`() {
    val limiter = TokenBucketRateLimiter(3, FakeClock())
    repeat(3) { assertTrue(limiter.tryAcquire("pod")) }
    assertFalse(limiter.tryAcquire("pod"))
  }

  @Test fun `a zero capacity beside a positive rate is refused at construction`() {
    // It would refuse every request forever, which no caller can mean.
    assertFailsWith<IllegalArgumentException> { TokenBucketRateLimiter(10, burstCapacity = 0) }
    // A disabled limiter reads neither value, so it stays constructible.
    assertTrue(TokenBucketRateLimiter(0, burstCapacity = 0).tryAcquire("pod"))
  }

  @Test fun `a caller minting a fresh key per request cannot grow the map without bound`() {
    // The key may hold something the caller chose, so varying it allocates a bucket per request and
    // an idle sweep answers none of it — nothing is idle yet. The cap is what bounds the map,
    // however fast the keys arrive; a budget is still spent per key, which is the residual.
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(20, clock, burstCapacity = 300, maxKeys = 8)
    repeat(500) { assertTrue(limiter.tryAcquire("minted-$it")) }
  }

  @Test fun `a full map never refuses a key it has not seen`() {
    // The map is shared, so refusing on a full map would let whoever keeps it full reserve it: every
    // OTHER first-time key would be turned away while the hoarder's own buckets went on being
    // served. Eviction costs a key its accumulated budget; refusal would cost everyone else the
    // endpoint.
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(20, clock, burstCapacity = 5, maxKeys = 4)
    // A hoarder fills the map and keeps every entry warm, which defeats both idle cutoffs.
    repeat(4) { assertTrue(limiter.tryAcquire("hoarder-$it")) }
    repeat(20) {
      clock.advance(1_000)
      repeat(4) { i -> limiter.tryAcquire("hoarder-$i") }
      assertTrue(limiter.tryAcquire("newcomer-$it"), "a first-time key was refused for want of room")
    }
  }

  @Test fun `an established key keeps its budget while the map is full`() {
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(20, clock, burstCapacity = 300, maxKeys = 4)
    assertTrue(limiter.tryAcquire("real"))
    repeat(30) { assertTrue(limiter.tryAcquire("minted-$it")) }
    repeat(20) { assertTrue(limiter.tryAcquire("real")) }
  }

  @Test fun `a full map releases single-use keys first`() {
    // The flood's keys are touched once; a caller's is touched repeatedly. The tighter cutoff while
    // full is what tells the two apart, so eviction lands on the flood rather than on the traffic.
    val clock = FakeClock()
    val limiter = TokenBucketRateLimiter(20, clock, burstCapacity = 2, maxKeys = 4)
    assertTrue(limiter.tryAcquire("real"))       // spends one of two
    repeat(3) { assertTrue(limiter.tryAcquire("minted-$it")) }

    clock.advance(61_000)
    assertTrue(limiter.tryAcquire("real"))        // touched again, so it is not the idle one
    assertTrue(limiter.tryAcquire("newcomer"))    // needs room, and the minted keys are what give it

    // `real` kept its bucket rather than being evicted and handed a fresh burst: the refill at the
    // clock step took it back to two, one of which it has spent.
    assertTrue(limiter.tryAcquire("real"))
    assertFalse(limiter.tryAcquire("real"))
  }

  @Test fun `the ceiling holds when first-time keys arrive together`() {
    // A size check followed by an insert bounds nothing under concurrency: every thread reads the
    // same size, each frees one slot, and each inserts. Without the admission lock the map ends up
    // over the ceiling by however many threads were in flight, which is the heap growth the cap
    // exists to stop.
    val limiter = TokenBucketRateLimiter(1000, maxKeys = 64)
    val threads = 16
    val perThread = 500
    val ready = java.util.concurrent.CountDownLatch(threads)
    val go = java.util.concurrent.CountDownLatch(1)
    val workers = (0 until threads).map { t ->
      Thread {
        ready.countDown()
        go.await()
        repeat(perThread) { i -> limiter.tryAcquire("thread-$t-key-$i") }
      }.apply { start() }
    }
    ready.await()
    go.countDown()
    workers.forEach { it.join() }

    assertTrue(
      limiter.trackedKeyCount() <= 64,
      "the map grew past its ceiling under ${threads * perThread} concurrent first-time keys: " +
          "${limiter.trackedKeyCount()}",
    )
  }

  @Test fun `two threads racing on the same new key share one bucket`() {
    // The re-read inside the lock: without it both threads insert, and the second overwrites the
    // first - so the caller that just spent a permit gets a full burst back.
    val limiter = TokenBucketRateLimiter(1, maxKeys = 64)
    val go = java.util.concurrent.CountDownLatch(1)
    val admitted = java.util.concurrent.atomic.AtomicInteger()
    val workers = (0 until 8).map {
      Thread {
        go.await()
        if (limiter.tryAcquire("contested")) admitted.incrementAndGet()
      }.apply { start() }
    }
    go.countDown()
    workers.forEach { it.join() }

    assertEquals(1, admitted.get(), "one permit was handed out more than once")
  }

  @Test fun `distinct keys have independent budgets`() {
    val limiter = TokenBucketRateLimiter(1)
    assertTrue(limiter.tryAcquire("pod-a"))
    assertFalse(limiter.tryAcquire("pod-a"))
    assertTrue(limiter.tryAcquire("pod-b"))
  }
}
