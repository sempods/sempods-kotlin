package org.sempods.client.core

import java.time.Duration

/**
 * What is left of one operation's whole-operation deadline.
 *
 * Created once when the operation starts and consulted at every step that can block — admission,
 * credential acquisition, each attempt, the body handler. A deadline that were re-derived per step
 * would give each step the full budget and bound nothing.
 *
 * [Duration.ZERO] means unbounded, which is the opt-out a long-lived stream needs.
 */
internal class SempodsDeadline private constructor(private val deadlineNanos: Long?) {

  /** Milliseconds left, or `-1` when unbounded. Never `0` while time remains: rounds up. */
  fun remainingMillis(): Long {
    val deadline = deadlineNanos ?: return -1
    val remaining = deadline - System.nanoTime()
    if (remaining <= 0) throw SempodsTransportException("The operation deadline elapsed.")
    return (remaining / 1_000_000).coerceAtLeast(1)
  }

  /** Throws when nothing is left, and returns otherwise. */
  fun failIfElapsed() {
    remainingMillis()
  }

  /** What one attempt may take, so the engine's own call timeout never outlives the operation. */
  fun attemptTimeout(): Duration? {
    val deadline = deadlineNanos ?: return null
    return Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(1))
  }

  companion object {

    fun of(timeout: Duration): SempodsDeadline =
      if (timeout.isZero) SempodsDeadline(null)
      else SempodsDeadline(System.nanoTime() + timeout.toNanos())
  }
}
