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
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit — the budget, the key it is spent against, and the sampled log line. */
class PodTokenRateLimiterTest {

  private val appender = ListAppender<ILoggingEvent>()

  private lateinit var logger: Logger

  @BeforeEach
  fun attachAppender() {
    logger = logbackContext().getLogger(PodTokenRateLimiter::class.java)
    appender.start()
    logger.addAppender(appender)
  }

  @AfterEach
  fun detachAppender() {
    logger.detachAppender(appender)
    appender.stop()
  }

  /**
   * SLF4J hands a `SubstituteLoggerFactory` to every thread but the one currently binding the
   * provider, and this suite runs its classes concurrently — so a plain cast is flaky. The same
   * window `ApiExceptionMapperTest` waits out, and it closes in microseconds.
   */
  private fun logbackContext(): LoggerContext {
    repeat(500) {
      val factory = LoggerFactory.getILoggerFactory()
      if (factory is LoggerContext) return factory
      Thread.sleep(10)
    }
    error("logback never became the SLF4J binding of this test JVM")
  }

  /** The refusal lines this case produced, told from a sibling's by the key they name. */
  private fun loggedLinesFor(key: String): List<String> =
    appender.list.map { it.formattedMessage }.filter { "client='$key'" in it }

  /** Every refusal line naming this address, on either tier. */
  private fun loggedLinesFromAddress(address: String): List<String> =
    appender.list.map { it.formattedMessage }
      .filter { "client='$address|" in it || "address='$address'" in it }

  /** The grant a browser retries with, and the one whose caller is named in the form field. */
  private val REFRESH = "refresh_token"

  /** The grant whose caller is named in the Basic header instead. */
  private val CREDENTIALS = "client_credentials"

  private val chat = "did:web:apps.sempods.org:chat"
  private val proxied = "198.51.100.4, 203.0.113.7"

  /** The per-client tier under test, with the address tier deliberately out of the way. */
  private fun limiter(permitsPerMinute: Int, clock: FakeClock = FakeClock()) =
    PodTokenRateLimiter(permitsPerMinute, clock, addressPerMinute = 0)

  private fun basic(username: String, password: String = "s3cret"): String =
    "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

  @Test fun `a non-positive budget disables the limit`() {
    val limiter = limiter(0)
    repeat(1000) { assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null)) }
  }

  @Test fun `the off switch leaves no tier running`() {
    // What an operator gets from `SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE=0` once `SempodsConfig`
    // has applied its rule: both rates zero, and nothing refused however hard the endpoint is hit.
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 0,
      clock = FakeClock(),
      addressPerMinute = SempodsConfig.resolveAddressRateLimit(perMinute = 0, addressPerMinute = 100),
    )
    repeat(2000) { assertTrue(limiter.tryAcquire(proxied, REFRESH, "dyn:whatever-$it", null)) }
  }

  @Test fun `the budget is spent and then the caller is refused`() {
    val limiter = limiter(3)
    repeat(3) { assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null)) }
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))
  }

  @Test fun `the budget refills over time`() {
    val clock = FakeClock()
    val limiter = limiter(60, clock) // one permit per second
    repeat(60) { assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null)) }
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))
    clock.advance(1_000)
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
  }

  @Test fun `the same app from two addresses has two budgets`() {
    // The objection to keying on the client id alone: four WebIDs presented this same static
    // `did:web:` id in the logs behind this class, and one broken installation must not become a
    // throttle on everyone else running the app.
    val limiter = limiter(1)
    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    assertTrue(limiter.tryAcquire("203.0.113.9", REFRESH, chat, null))
  }

  @Test fun `two apps behind one address have two budgets`() {
    // The objection to keying on the address alone: a shared NAT, and a service client whose
    // mints would otherwise land in the same bucket as every browser behind that address.
    val limiter = limiter(1)
    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, "dyn:other", null))
  }

  @Test fun `one client hammering several pods stays one budget`() {
    // The multiplier: the pod is deliberately absent from the key, so a browser holding grants on
    // five pods cannot spend five budgets — every pod would otherwise see a fifth of the traffic
    // the client is actually causing. Nothing about the pod reaches this class to begin with,
    // which is what the assertion pins.
    val limiter = limiter(2)
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))
  }

  @Test fun `client_credentials is keyed by the name in its Basic credential`() {
    val limiter = limiter(1)
    assertTrue(limiter.tryAcquire(proxied, CREDENTIALS, null, basic("service-a")))
    assertFalse(limiter.tryAcquire(proxied, CREDENTIALS, null, basic("service-a")))
    // A different password is the same client, and shares its budget.
    assertFalse(limiter.tryAcquire(proxied, CREDENTIALS, null, basic("service-a", password = "rotated")))
    assertTrue(limiter.tryAcquire(proxied, CREDENTIALS, null, basic("service-b")))
  }

  @Test fun `the grant decides which name identifies the caller, not which one is present`() {
    // Each branch of the endpoint reads exactly one of the two: `client_credentials` authenticates
    // the Basic username and never sees the form field, the others authenticate the form field and
    // ignore the header. Reading whichever happens to be present would hand a caller two key
    // spaces — invent Basic usernames on a refresh, or vary a form field on a service call, and
    // either draws a fresh bucket every time while spending only the address aggregate.
    val limiter = limiter(1)

    // On `client_credentials` the form field is not the caller, so varying it changes nothing.
    assertTrue(limiter.tryAcquire(proxied, CREDENTIALS, chat, basic("service-a")))
    assertFalse(limiter.tryAcquire(proxied, CREDENTIALS, "dyn:renamed", basic("service-a")))

    // On a refresh the header is not the caller, so varying *it* changes nothing either.
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, basic("invented-name")))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, basic("another-invented-name")))

    // And the two grants' namespaces do not collide: `service-a` and `chat` are separate buckets,
    // both already spent above, while a third name is still fresh.
    assertTrue(limiter.tryAcquire(proxied, REFRESH, "dyn:third", null))
  }

  @Test fun `the refusal names the tier that refused, not the one it did not reach`() {
    // A newcomer behind a busy NAT has spent nothing of its own. Logging it against the per-client
    // budget sends whoever reads the line after the wrong caller — the failure OR5 is about.
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 20, clock = FakeClock(), addressPerMinute = 1,
    )
    assertTrue(limiter.tryAcquire(proxied, REFRESH, "dyn:noisy-neighbour", null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, "dyn:newcomer", null))

    val line = appender.list.map { it.formattedMessage }.single { "203.0.113.7" in it }
    assertTrue("address budget" in line, line)
    assertTrue("permitsPerMinute=1" in line, line)          // the address rate, not the client's 20
    assertTrue("dyn:newcomer" !in line, "the line blamed a caller that spent nothing: $line")
  }

  @Test fun `a refusal by the client tier says so`() {
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 1, clock = FakeClock(), addressPerMinute = 100,
    )
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))

    val line = appender.list.map { it.formattedMessage }.single { "203.0.113.7" in it }
    assertTrue("client budget" in line, line)
    assertTrue(chat in line, line)
  }

  @Test fun `a client_id nobody agreed a length for does not decide what a key costs`() {
    // The map ceiling bounds how many keys are held, not how big they are, and this half of the key
    // is a form field on an unauthenticated endpoint.
    val limiter = limiter(1)
    val huge = "dyn:" + "x".repeat(100_000)
    val hugeSibling = "dyn:" + "x".repeat(99_999) + "y"

    assertTrue(limiter.tryAcquire(proxied, REFRESH, huge, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, huge, null))   // folded, but still itself
    assertTrue(limiter.tryAcquire(proxied, REFRESH, hugeSibling, null))  // and still distinct from its twin

    // What reaches the log is the folded key, so a caller cannot choose the size of a warning.
    val line = appender.list.map { it.formattedMessage }.single { "sha256:" in it }
    assertTrue(line.length < 500, "the refusal line carried a caller-sized key: ${line.length} chars")
  }

  @Test fun `an ordinary client id is kept whole`() {
    // Folding is for the pathological case; a real name has to stay readable in the log.
    val limiter = limiter(0)
    limiter.tryAcquire(proxied, REFRESH, chat, null)
    assertTrue(appender.list.none { "sha256:" in it.formattedMessage })
  }

  @Test fun `a request naming no client at all still lands in a bucket`() {
    val limiter = limiter(1)
    assertTrue(limiter.tryAcquire(proxied, REFRESH, null, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, "   ", null))
  }

  @Test fun `a forged leftmost forwarded-for entry does not buy a fresh budget`() {
    val limiter = limiter(1)
    assertTrue(limiter.tryAcquire("1.2.3.4, 203.0.113.7", REFRESH, chat, null))
    // Same client, a different value invented for the left-hand side: the proxy's entry is still
    // the last one, so it is still the same bucket.
    assertFalse(limiter.tryAcquire("9.9.9.9, 203.0.113.7", REFRESH, chat, null))
    assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
  }

  @Test fun `without a forwarded-for header nothing is limited`() {
    // No proxy spoke, so one client cannot be told from another, and a single shared bucket for
    // everything arriving that way would be an outage rather than a limit.
    val limiter = limiter(1)
    repeat(100) { assertTrue(limiter.tryAcquire(null, REFRESH, chat, null)) }
    repeat(100) { assertTrue(limiter.tryAcquire("  ", REFRESH, chat, null)) }
  }

  @Test fun `a caller renaming itself on every request still gets one log line a minute`() {
    // The sampler is keyed by address for exactly this: a fresh `client_id` is a fresh bucket with
    // a fresh permit in it, so sampling on the pair the budget is spent against would log every
    // single refusal — the 102,642-line flood this class exists to prevent, rebuilt by the log
    // rather than by the endpoint.
    val clock = FakeClock()
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 100, clock = clock, addressPerMinute = 1,
    )
    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, "dyn:first", null))
    repeat(200) { assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, "dyn:minted-$it", null)) }

    assertEquals(1, loggedLinesFromAddress("203.0.113.7").size)

    clock.advance(60_000)
    repeat(50) { limiter.tryAcquire("203.0.113.7", REFRESH, "dyn:later-$it", null) }
    assertEquals(2, loggedLinesFromAddress("203.0.113.7").size)
  }

  @Test fun `a client_id carrying a line break cannot forge a second log line`() {
    // This module is published, so the console `%replace` that covers this repository's own
    // applications is not a guarantee here: an embedder brings its own logging configuration.
    // `docs/logging.md` §"Three rules" — the half that survives the encoder.
    val limiter = limiter(1)
    val forged = "dyn:abc\n2026-01-01 21:00:00,000 WARN  [jetty] forged"

    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, forged, null))
    assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, forged, null))

    val line = appender.list.map { it.formattedMessage }.single { "dyn:abc" in it }
    assertFalse("\n" in line, "the refusal line carries a raw newline: $line")
    assertTrue("\\u000a" in line, line)
  }

  @Test fun `the address is counted before the client, so varying the client buys nothing`() {
    // `client_id` is a form parameter on an unauthenticated endpoint. Counting it first would let a
    // caller draw a fresh per-client budget on every request simply by renaming itself.
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 100, clock = FakeClock(), addressPerMinute = 3,
    )
    repeat(3) { assertTrue(limiter.tryAcquire(proxied, REFRESH, "dyn:minted-$it", null)) }
    repeat(20) { assertFalse(limiter.tryAcquire(proxied, REFRESH, "dyn:minted-fresh-$it", null)) }
  }

  @Test fun `the address tier is an aggregate, not a second per-client budget`() {
    // Two well-behaved clients behind one address share the address budget while keeping their own.
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 100, clock = FakeClock(), addressPerMinute = 4,
    )
    repeat(2) { assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null)) }
    repeat(2) { assertTrue(limiter.tryAcquire(proxied, REFRESH, "dyn:other", null)) }
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))
    assertFalse(limiter.tryAcquire(proxied, REFRESH, "dyn:other", null))
  }

  @Test fun `one address spending its aggregate does not touch another`() {
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 100, clock = FakeClock(), addressPerMinute = 2,
    )
    repeat(2) { assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null)) }
    assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    assertTrue(limiter.tryAcquire("203.0.113.9", REFRESH, chat, null))
  }

  @Test fun `the address tier refills like any other bucket`() {
    val clock = FakeClock()
    val limiter = PodTokenRateLimiter(
      permitsPerMinute = 100, clock = clock, addressPerMinute = 60, addressBurst = 60,
    )
    repeat(60) { assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null)) }
    assertFalse(limiter.tryAcquire(proxied, REFRESH, chat, null))
    clock.advance(1_000)
    assertTrue(limiter.tryAcquire(proxied, REFRESH, chat, null))
  }

  @Test fun `a hammered key is logged once a minute, not once a request`() {
    // The whole reason the sampler exists: the traffic this class answers was 102,642 refusals in
    // twenty-one hours, and a warning apiece would rebuild exactly the volume being removed.
    val clock = FakeClock()
    val limiter = limiter(1, clock)
    val key = "203.0.113.7|$chat"

    assertTrue(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null))
    repeat(50) { assertFalse(limiter.tryAcquire("203.0.113.7", REFRESH, chat, null)) }
    assertEquals(1, loggedLinesFor(key).size)

    // A different key is a different sampler bucket, so it is not silenced by the first one.
    assertTrue(limiter.tryAcquire("203.0.113.9", REFRESH, chat, null))
    repeat(20) { assertFalse(limiter.tryAcquire("203.0.113.9", REFRESH, chat, null)) }
    assertEquals(1, loggedLinesFor("203.0.113.9|$chat").size)

    // The next minute admits one more line for the key that is still being hammered.
    clock.advance(60_000)
    repeat(50) { limiter.tryAcquire("203.0.113.7", REFRESH, chat, null) }
    assertEquals(2, loggedLinesFor(key).size)
  }
}
