package org.sempods.api.pod.system.auth

import org.sempods.SempodsConfig
import org.sempods.commons.logging.CapturedLog
import org.sempods.commons.ratelimit.FakeClock
import org.sempods.pods.PodId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit: the three registration budgets and what each is keyed by.
 *
 * Every case uses addresses and pods of its own, so a log line is attributed to a case by the key
 * it names.
 */
class PodRegistrationRateLimiterTest {

  private fun limiter(
    clock: FakeClock = FakeClock(),
    public: Int = 2,
    protected: Int = 2,
    installer: Int = 2,
  ) = PodRegistrationRateLimiter(
    clock = clock,
    publicPerMinute = public,
    publicBurst = public,
    protectedPerMinute = protected,
    protectedBurst = protected,
    installerPerMinute = installer,
    installerBurst = installer,
  )

  /** A proxied request whose appended address is [address]. */
  private fun via(address: String) = "198.51.100.4, $address"

  @Test fun `a public caller spends its budget and is then refused`() {
    val limiter = limiter()
    repeat(2) { assertTrue(limiter.tryAcquireAddress(via("203.0.113.1"), bearerPresented = false)) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.1"), bearerPresented = false))
  }

  @Test fun `the budget refills over time`() {
    val clock = FakeClock()
    val limiter = limiter(clock)
    repeat(2) { limiter.tryAcquireAddress(via("203.0.113.2"), bearerPresented = false) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.2"), bearerPresented = false))

    clock.advance(30_000)

    assertTrue(limiter.tryAcquireAddress(via("203.0.113.2"), bearerPresented = false))
  }

  @Test fun `the public and the protected budget of one address are separate`() {
    val limiter = limiter()
    repeat(2) { limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = false) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = false))

    repeat(2) { assertTrue(limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = true)) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = true))
  }

  @Test fun `two addresses have two budgets`() {
    val limiter = limiter()
    repeat(2) { limiter.tryAcquireAddress(via("203.0.113.4"), bearerPresented = false) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.4"), bearerPresented = false))

    assertTrue(limiter.tryAcquireAddress(via("203.0.113.5"), bearerPresented = false))
  }

  @Test fun `a forged leftmost entry does not buy a fresh budget`() {
    val limiter = limiter()
    repeat(2) { limiter.tryAcquireAddress("10.0.0.$it, 203.0.113.6", bearerPresented = false) }
    assertFalse(limiter.tryAcquireAddress("10.0.0.9, 203.0.113.6", bearerPresented = false))
  }

  @Test fun `without a forwarded-for header no address is limited`() {
    val limiter = limiter()
    repeat(10) {
      assertTrue(limiter.tryAcquireAddress(null, bearerPresented = false))
      assertTrue(limiter.tryAcquireAddress(null, bearerPresented = true))
    }
  }

  @Test fun `an installation is budgeted per pod`() {
    val limiter = limiter()
    repeat(2) { assertTrue(limiter.tryAcquire(PodId("pod-a"))) }
    assertFalse(limiter.tryAcquire(PodId("pod-a")))

    assertTrue(limiter.tryAcquire(PodId("pod-b")), "another pod")
  }

  @Test fun `a rate of zero turns that budget off and leaves the others`() {
    val limiter = limiter(public = 0)
    repeat(10) { assertTrue(limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = false)) }

    repeat(2) { limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = true) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = true))
  }

  @Test fun `the configured burst is allowed at once, and a burst of 0 is the rate`() {
    val limiter = PodRegistrationRateLimiter(
      SempodsConfig(
        httpPort = 8090,
        apiBaseUrl = "https://example.org/",
        mongoUrl = "mongodb://localhost:27018",
        mongoDb = "pods",
        oauthErrorDocBase = null,
        registerRateLimitPublicPerMinute = 1,
        registerRateLimitPublicBurst = 4,
        registerRateLimitProtectedPerMinute = 1,
        registerRateLimitInstallerPerMinute = 1,
      ),
    )
    repeat(4) { assertTrue(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = false)) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = false))

    assertTrue(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = true))
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = true))
  }

  @Test fun `a hammered key is logged once a minute and names its budget`() {
    val clock = FakeClock()
    val limiter = limiter(clock)
    val lines = CapturedLog.linesFrom(PodRegistrationRateLimiter::class.java) {
      repeat(20) { limiter.tryAcquireAddress(via("203.0.113.9"), bearerPresented = false) }
      clock.advance(60_000)
      repeat(20) { limiter.tryAcquireAddress(via("203.0.113.9"), bearerPresented = false) }
    }.filter { "key='203.0.113.9'" in it }

    assertEquals(2, lines.size, lines.toString())
    assertTrue(lines.all { "public budget" in it }, lines.toString())
  }

  @Test fun `an address carrying a line break cannot forge a log line`() {
    val limiter = limiter(public = 1)
    val lines = CapturedLog.linesFrom(PodRegistrationRateLimiter::class.java) {
      repeat(2) { limiter.tryAcquireAddress(via("203.0.113.10\n[oauth/register] forged"), bearerPresented = false) }
    }.filter { "203.0.113.10" in it }

    assertEquals(1, lines.size, lines.toString())
    assertFalse('\n' in lines.single(), lines.single())
  }
}
