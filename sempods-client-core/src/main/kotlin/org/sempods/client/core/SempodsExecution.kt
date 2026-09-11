package org.sempods.client.core

import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One operation, from admission to the last byte the handler reads.
 *
 * **Why the whole loop lives in one place.** Authentication, the attempt budget, the deadline, the
 * cancellation checks and the admission slot are not independent: a retry is legal only when the
 * body can be replayed *and* the handler has seen nothing *and* the budget and deadline allow it,
 * and a slot must be released on every one of those exits. Spread across an interceptor, a client
 * and a caller, that is four places to get the same condition right.
 *
 * **Only this class authorizes a retry.** [SempodsRequestAuth.recover] says whether another attempt
 * would differ; whether one may happen is decided here, and at most once.
 */
internal class SempodsExecution(
  private val transport: SempodsTransport,
  private val auth: SempodsRequestAuth,
  private val defaultTimeout: Duration,
) {

  fun <T> run(
    request: SempodsRequest,
    handler: SempodsBodyHandler<T>,
    operation: SempodsOperation,
  ): SempodsResponse<T> {
    val deadline = SempodsDeadline.of(request.operationTimeout ?: defaultTimeout)
    transport.guardTarget(request.uri)
    operation.failIfCancelled()
    transport.admit(deadline)
    return try {
      attemptLoop(request, handler, operation, deadline)
    } catch (e: HandlerFailure) {
      throw e.cause!!
    } finally {
      transport.release()
    }
  }

  private fun <T> attemptLoop(
    request: SempodsRequest,
    handler: SempodsBodyHandler<T>,
    operation: SempodsOperation,
    deadline: SempodsDeadline,
  ): SempodsResponse<T> {
    var attempt = 1
    while (true) {
      // Sticky between attempts as well as before the first: an abort arriving while a credential
      // was being refreshed must not start the request it was refreshed for.
      operation.failIfCancelled()
      deadline.failIfElapsed()

      val headers = authenticated(request, attempt, deadline)
      val recovery = RecoveryProbe(attempt, retryAllowed(request, attempt))

      val response = transport.attempt(
        uri = request.uri,
        method = request.method,
        headers = headers,
        body = request.body,
        callTimeout = deadline.attemptTimeout(),
        operation = operation,
        read = { streamed -> recovery.readOrProbe(streamed, handler) },
      )
      if (response != null) return response
      attempt += 1
    }
  }

  /**
   * Whether a further attempt could be made at all, asked *before* the response arrives.
   *
   * A body that can be written once rules one out, and so does a budget of one extra attempt that
   * has been spent. Asking here rather than after the fact is what keeps the answer independent of
   * what the handler did.
   */
  private fun retryAllowed(request: SempodsRequest, attempt: Int): Boolean =
    attempt == 1 && request.replayable

  /** Applies the session's authentication to a fresh copy of the request's headers. */
  private fun authenticated(
    request: SempodsRequest,
    attempt: Int,
    deadline: SempodsDeadline,
  ): List<Pair<String, String>> {
    val mutable = MutableAuthRequest(request, attempt, deadline)
    auth.apply(mutable)
    return mutable.headers()
  }

  /**
   * Decides, inside the body's lifetime, whether this response is the answer or a challenge.
   *
   * **The order matters and is the reason this is not two steps.** Once [SempodsBodyHandler] has
   * been called the response is the caller's, and no retry may follow — a handler that already
   * streamed half a body cannot be run again against a second one. So the challenge is read first,
   * from a bounded snippet, and the handler only runs when no retry is going to happen.
   */
  private inner class RecoveryProbe(private val attempt: Int, private val retryPossible: Boolean) {

    fun <T> readOrProbe(
      streamed: SempodsStreamedResponse,
      handler: SempodsBodyHandler<T>,
    ): SempodsResponse<T>? {
      if (!retryPossible || streamed.statusCode != 401) {
        return SempodsResponse(streamed.statusCode, streamed.headers, guarded(streamed, handler))
      }

      // The body is buffered, bounded, *before* anything else reads it. A challenge has to be read
      // to be answered, and a stream cannot be read twice — so when no retry follows, the handler
      // is given those same bytes back rather than a stream somebody already drained.
      val bytes = streamed.bodyStream().readNBytes(SempodsHttpException.MAX_ERROR_BODY_CHARS)
      val challenge =
        SempodsAuthChallenge(streamed.statusCode, streamed.headers, String(bytes, UTF_8), attempt)
      if (auth.recover(challenge).shouldRetry) return null

      val buffered =
        SempodsStreamedResponse(streamed.statusCode, streamed.headers, ByteArrayInputStream(bytes))
      return SempodsResponse(streamed.statusCode, streamed.headers, guarded(buffered, handler))
    }

    /**
     * Runs the handler and keeps its failure its own.
     *
     * Without this the transport's `catch (IOException)` would swallow a decoding failure and
     * report it as a connection that broke — the two are diagnosed completely differently, and a
     * caller cannot tell them apart once the type is gone.
     */
    private fun <T> guarded(response: SempodsStreamedResponse, handler: SempodsBodyHandler<T>): T =
      try {
        handler.handle(response)
      } catch (e: Throwable) {
        throw HandlerFailure(e)
      }
  }
}

/** Carries a body handler's own failure out through the transport's I/O handling, unchanged. */
private class HandlerFailure(cause: Throwable) : RuntimeException(cause)

/**
 * The request as an authentication mechanism sees it: metadata, and headers it may change.
 *
 * Built fresh for every attempt over the caller's original header list, so a header the mechanism
 * sets is regenerated rather than accumulated — which is what a request-bound header needs, and
 * what stops a second attempt from carrying both credentials.
 */
private class MutableAuthRequest(
  private val request: SempodsRequest,
  private val attempt: Int,
  private val deadline: SempodsDeadline,
) : SempodsAuthRequest {

  private val mutable = request.headers.toMutableList()

  override fun method(): String = request.method

  override fun uri(): URI = request.uri

  override fun attempt(): Int = attempt

  override fun remainingTimeoutMillis(): Long = deadline.remainingMillis()

  override fun setHeader(name: String, value: String) {
    removeHeader(name)
    mutable += name to value
  }

  override fun addHeader(name: String, value: String) {
    mutable += name to value
  }

  override fun removeHeader(name: String) {
    mutable.removeAll { it.first.equals(name, ignoreCase = true) }
  }

  fun headers(): List<Pair<String, String>> = mutable.toList()
}

private val UTF_8 = StandardCharsets.UTF_8
