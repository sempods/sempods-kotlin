package org.sempods.api.pod.system.auth

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.sempods.SempodsConfig
import org.sempods.commons.ratelimit.FakeClock
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit: the three registration budgets and what each is keyed by.
 *
 * Every case uses addresses and subjects of its own, so the cases share nothing, the log
 * appender included: a line is attributed to a case by the key it names.
 */
class PodRegistrationRateLimiterTest {

  private val appender = ListAppender<ILoggingEvent>()

  private lateinit var logger: Logger

  @BeforeEach
  fun attachAppender() {
    logger = logbackContext().getLogger(PodRegistrationRateLimiter::class.java)
    appender.start()
    logger.addAppender(appender)
  }

  @AfterEach
  fun detachAppender() {
    logger.detachAppender(appender)
    appender.stop()
  }

  /** The window `PodTokenRateLimiterTest.logbackContext` waits out, for the same reason. */
  private fun logbackContext(): LoggerContext {
    repeat(500) {
      val factory = LoggerFactory.getILoggerFactory()
      if (factory is LoggerContext) return factory
      Thread.sleep(10)
    }
    error("logback never became the SLF4J binding of this test JVM")
  }

  private fun linesNaming(key: String): List<String> =
    appender.list.map { it.formattedMessage }.filter { "key='$key'" in it }

  private fun limiter(
    clock: FakeClock = FakeClock(),
    public: Int = 2,
    protected: Int = 2,
    installer: Int = 2,
  ) = PodRegistrationRateLimiter(
    clock = clock,
    publicPerMinute = public,
    protectedPerMinute = protected,
    installerPerMinute = installer,
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

    assertTrue(limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = true))

    repeat(1) { limiter.tryAcquireAddress(via("203.0.113.3"), bearerPresented = true) }
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

  @Test fun `an installer is budgeted per pod`() {
    val limiter = limiter()
    repeat(2) { assertTrue(limiter.tryAcquireInstallation("pod-a")) }
    assertFalse(limiter.tryAcquireInstallation("pod-a"))

    assertTrue(limiter.tryAcquireInstallation("pod-b"), "another pod")
  }

  @Test fun `a rate of zero turns that tier off and leaves the others`() {
    val limiter = limiter(public = 0)
    repeat(10) { assertTrue(limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = false)) }

    repeat(2) { limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = true) }
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.7"), bearerPresented = true))
  }

  @Test fun `the configured burst is allowed at once`() {
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

    // A burst of 0 is the rate.
    assertTrue(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = true))
    assertFalse(limiter.tryAcquireAddress(via("203.0.113.8"), bearerPresented = true))
  }

  @Test fun `a hammered key is logged once a minute and names its tier`() {
    val clock = FakeClock()
    val limiter = limiter(clock)
    repeat(20) { limiter.tryAcquireAddress(via("203.0.113.9"), bearerPresented = false) }

    val lines = linesNaming("203.0.113.9")
    assertEquals(1, lines.size, lines.toString())
    assertTrue("public budget" in lines.single(), lines.single())

    clock.advance(60_000)
    repeat(20) { limiter.tryAcquireAddress(via("203.0.113.9"), bearerPresented = false) }
    assertEquals(2, linesNaming("203.0.113.9").size)
  }

  @Test fun `an address carrying a line break cannot forge a log line`() {
    val limiter = limiter(public = 1)
    val address = "203.0.113.10\n[oauth/register] forged"
    repeat(2) { limiter.tryAcquireAddress(via(address), bearerPresented = false) }

    val lines = appender.list.map { it.formattedMessage }.filter { "203.0.113.10" in it }
    assertEquals(1, lines.size, lines.toString())
    assertFalse('\n' in lines.single(), lines.single())
  }
}
