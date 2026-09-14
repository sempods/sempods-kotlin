package org.sempods.client.core

/**
 * How many calls may be in flight on a client at once, and how many may wait for a turn.
 *
 * **Two numbers, because a queue is not capacity.** Bounding only the active ones lets every
 * further caller pile up behind them, and a slow server then turns into an unbounded queue in this
 * process — memory that grows until something else fails. Over [maxWaiting] a caller is refused
 * immediately, which is an answer it can act on.
 *
 * **It bounds the calls running on a client [SempodsOkHttp.install] configured**, a session's or not.
 * An enqueued call first waits in OkHttp's dispatcher queue, which the dispatcher's `maxRequests` and
 * `maxRequestsPerHost` bound, not these numbers. A call holds its slot from before its first attempt
 * until its response is closed, credential work included. A call a credential supplier makes through
 * the same client, on the thread it was called on, runs on the slot of the call it serves; one made on
 * another thread or through another client needs a slot of its own and can wait for it up to its
 * deadline. A caller waits for a
 * slot no longer than its call deadline, and `Call.cancel()` ends the wait.
 */
data class SempodsAdmission @JvmOverloads constructor(
  val maxActive: Int = 64,
  val maxWaiting: Int = 256,
) {
  init {
    require(maxActive > 0) { "maxActive must be positive, not $maxActive" }
    require(maxWaiting >= 0) { "maxWaiting must not be negative, not $maxWaiting" }
  }
}
