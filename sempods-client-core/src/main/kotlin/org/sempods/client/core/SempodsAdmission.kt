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
 * `maxRequestsPerHost` bound, not these numbers. A call holds its slot while it sends and until its
 * response is closed. It gives the slot back while it acquires a credential, so a supplier can fetch
 * through the same client, and that work is bounded by the call's deadline; a refusal its credential
 * does not recover is handed back without a slot. A caller waits for a slot no longer than its call
 * deadline, and `Call.cancel()` ends the wait.
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
