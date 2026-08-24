package org.sempods.mcp.api.mcp

import org.sempods.commons.ratelimit.FakeClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRateLimiterTest {

  private val alice = "https://id.test/e/alice"
  private val bob = "https://id.test/e/bob"

  @Test fun `a non-positive budget disables the quota`() {
    val limiter = UserRateLimiter(0)
    repeat(1000) { assertTrue(limiter.tryAcquire(alice, "default")) }
  }

  @Test fun `the budget refills continuously over time`() {
    val clock = FakeClock()
    val limiter = UserRateLimiter(60, clock) // one permit per second
    repeat(60) { assertTrue(limiter.tryAcquire(alice, "default")) }
    assertFalse(limiter.tryAcquire(alice, "default"))
    clock.advance(1_000)
    assertTrue(limiter.tryAcquire(alice, "default"))
  }

  @Test fun `budgets are independent per user and per profile`() {
    val limiter = UserRateLimiter(1)
    assertTrue(limiter.tryAcquire(alice, "default"))
    assertFalse(limiter.tryAcquire(alice, "default"))
    // Same user, different profile — its own bucket (profiles are isolation bundles, M5).
    assertTrue(limiter.tryAcquire(alice, "private"))
    // Different user, same profile name — its own bucket.
    assertTrue(limiter.tryAcquire(bob, "default"))
  }
}
