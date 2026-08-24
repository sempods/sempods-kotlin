package org.sempods.commons.tests

import io.mockk.MockKVerificationScope
import io.mockk.Ordering
import io.mockk.verify
import org.awaitility.Awaitility
import org.awaitility.Awaitility.await
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object TestUtil {

  /**
   * The Awaitility settings every suite in this project polls with.
   *
   * `pollInSameThread` is the one that is not a preference: Awaitility polls from a thread of its
   * own by default, and the current [org.sempods.commons.trace.TraceContextHolder] — like any other
   * thread-local a test composes around — would not travel with it, so the condition would be
   * evaluated in a context the test never set up.
   *
   * Ten seconds is the budget, and a suite is expected to live inside it rather than raise it. A
   * longer wait does not make a slow path pass, it only makes a broken one expensive: six gcal
   * classes each sitting out a thirty-second timeout spent three minutes of one CI run waiting for
   * answers that were never coming.
   *
   * There is no exception. Where a suite could not hold the budget it was the runner that was
   * short of CPU, not the wait that was short of seconds — see the `--max-workers` note in
   * `.github/workflows/test.yml`.
   *
   * Called from each test composition's module. It names no framework, so it belongs beside the
   * other shared fixtures rather than inside any one composition.
   */
  fun initializeAwaitilityDefaults() {
    Awaitility.pollInSameThread()
    Awaitility.setDefaultTimeout(10, TimeUnit.SECONDS)
  }

  fun randomId(): String {
    return java.lang.Long.toHexString(Random.nextLong())
  }

  fun waitMs(ms: Long = 5L, clock: Clock = Clock.systemUTC()): Instant {
    return waitUntil(
      clock = clock,
      timestamp = clock.instant().plusMillis(ms),
    )
  }

  fun waitUntil(
    timestamp: Instant,
    clock: Clock = Clock.systemUTC(),
  ): Instant {
    var now = clock.instant()
    var delta = (timestamp.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
    while (delta > 0) {
      Thread.sleep(delta)
      now = clock.instant()
      delta = (timestamp.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
    }
    return now
  }

  fun awaitVerify(
    ordering: Ordering = Ordering.UNORDERED,
    inverse: Boolean = false,
    atLeast: Int = 1,
    atMost: Int = Int.MAX_VALUE,
    exactly: Int = -1,
    timeout: Long = 0,
    verifyBlock: MockKVerificationScope.() -> Unit,
  ) {
    await().untilAsserted {
      verify(
        ordering = ordering,
        inverse = inverse,
        atLeast = atLeast,
        atMost = atMost,
        exactly = exactly,
        timeout = timeout,
        verifyBlock = verifyBlock,
      )
    }
  }
}
