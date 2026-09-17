package org.sempods.client.core

import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * [request] with this mechanism applied for [attempt]; refused when it changed more than headers
 * ([SempodsRequestAuth] says why). The refusal names the URL without its query, where a token may be.
 */
@Throws(IOException::class)
internal fun SempodsRequestAuth.authenticate(request: Request, attempt: SempodsAuthAttempt): Request {
  val builder = request.newBuilder()
  apply(builder, attempt)
  val authenticated = builder.build()
  val changed = listOfNotNull(
    "target".takeIf { authenticated.url != request.url },
    "method".takeIf { authenticated.method != request.method },
    "body".takeIf { authenticated.body !== request.body },
  )
  if (changed.isNotEmpty()) {
    val described = request.url.newBuilder().query(null).fragment(null).build()
    throw SempodsClientException(
      "Authentication changed the ${changed.joinToString(" and ")} of '${request.method} $described'. " +
        "A mechanism may set headers and nothing else.",
    )
  }
  return authenticated
}

/**
 * Supplies a credential, and says whether a refused one is worth re-acquiring.
 *
 * A `String` rather than a parsed token: the core neither knows nor parses a token format. Whoever
 * implements this owns expiry, caching, whatever endpoint mints the value and the deadline for
 * minting it: a call's deadline cancels the call, but cannot interrupt a supplier that blocks. A
 * supplier fetching through the client it serves does so through [SempodsAuthAttempt.calls].
 */
fun interface SempodsCredentialSupplier {

  /**
   * The credential to send for [attempt]. [forceRefresh] is `true` when the previous value was refused,
   * so an implementation that caches must not answer from its cache.
   */
  @Throws(IOException::class)
  fun get(forceRefresh: Boolean, attempt: SempodsAuthAttempt): String
}

/**
 * How a session authenticates its requests — replaceable and decoratable without touching an
 * endpoint, a private internal or a central registration list.
 *
 * **Applied per attempt, on the request that is about to go out.** [apply] receives the builder, so
 * a header it sets replaces a same-named header the caller put on the request, and it is recomputed
 * for every attempt — which is the seam a later proof-of-possession mechanism needs, where the
 * header is bound to the request and to a nonce the server just supplied.
 *
 * **Headers, and nothing else.** A mechanism that changed the URL would carry the session's
 * credential to another authority, and one that changed the method or the body would send a request
 * the caller never built. The client's session interceptor compares all three after [apply] and
 * refuses the call.
 *
 * **Composition.** [andThen] applies the two in declaration order. On a challenge, [recover] is
 * asked in that same order and the first one to answer `true` wins; the rest are not asked.
 * However long the chain, one operation gets at most one authentication retry.
 *
 * **Concurrency.** An instance is shared by every call of its session and must be safe for
 * concurrent use. [refreshable] coalesces: concurrent callers that find the credential refused make
 * one acquisition between them, and a session with a different credential is not held up by it.
 */
fun interface SempodsRequestAuth {

  /** Puts this mechanism's headers on [request], which is about to be sent as [attempt]. */
  @Throws(IOException::class)
  fun apply(request: Request.Builder, attempt: SempodsAuthAttempt)

  /**
   * Whether another attempt would answer differently, having seen a refusal — a response outside 2xx.
   *
   * **An opinion, not an instruction.** Only the execution layer authorizes a retry, and it refuses
   * one for a non-replayable body or once the attempt budget is spent — so a mechanism returning
   * `true` on every challenge cannot loop. [response] is the refusal itself, headers included; its
   * body has not been read and must not be consumed here.
   */
  @Throws(IOException::class)
  fun recover(response: Response, attempt: SempodsAuthAttempt): Boolean = false

  fun andThen(next: SempodsRequestAuth): SempodsRequestAuth = Composite(listOf(this, next))

  private class Composite(val members: List<SempodsRequestAuth>) : SempodsRequestAuth {

    override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) =
      members.forEach { it.apply(request, attempt) }

    override fun recover(response: Response, attempt: SempodsAuthAttempt): Boolean =
      members.any { it.recover(response, attempt) }

    override fun andThen(next: SempodsRequestAuth): SempodsRequestAuth = Composite(members + next)
  }

  companion object {

    /**
     * No credential at all — the reader's profile.
     *
     * **A supported mode rather than a degraded one.** A pod serves its public contexts without a
     * bearer, and reading public data is what a consumer that owns no pod does. A 401 is
     * deliberately not retried: there is nothing to re-mint, so a second attempt would only double
     * the latency of a failure that was already final.
     *
     * Always the same instance: a [SempodsForeignTarget] call given anything else is one that carries a
     * credential.
     */
    @JvmStatic
    fun anonymous(): SempodsRequestAuth = ANONYMOUS

    private val ANONYMOUS = SempodsRequestAuth { _, _ -> }

    /**
     * A credential the caller already holds.
     *
     * A 401 is not retried for the same reason as [anonymous]: re-sending exactly what the server
     * just refused pays for the failure twice.
     */
    @JvmStatic
    fun bearer(token: String): SempodsRequestAuth =
      SempodsRequestAuth { request, _ -> request.header("Authorization", "Bearer $token") }

    /** A fixed value in a header of the deployment's choosing. Not retried, as [bearer]. */
    @JvmStatic
    fun apiKeyHeader(name: String, value: String): SempodsRequestAuth =
      SempodsRequestAuth { request, _ -> request.header(name, value) }

    /**
     * A bearer that can be re-acquired, and the only convenience that retries.
     *
     * On a 401 the credential is dropped and one further attempt is made with a freshly supplied
     * one; a supplier that fails fails the call with its exception. A refresh margin narrows the
     * expiry race but cannot close it, because a token can be rotated or revoked mid-flight — which
     * is why recovery exists at all rather than expiry handling alone.
     */
    @JvmStatic
    @JvmOverloads
    fun refreshable(
      supplier: SempodsCredentialSupplier,
      headerName: String = "Authorization",
      scheme: String = "Bearer",
    ): SempodsRequestAuth = Refreshable(supplier, headerName, scheme)
  }
}

/**
 * The refreshable bearer, with acquisition coalesced per credential.
 *
 * **A refusal says which credential it refused.** Threads refused at the same moment must make one
 * acquisition between them, and none may then throw away the value another just obtained. Recovery
 * therefore compares the header the refused request carried with the credential held now: when they
 * differ, another thread has replaced it already, and its value is the one to send.
 *
 * The lock is this object's, so it is per credential: a session authenticating against another pod
 * shares none of it and is never held up.
 */
private class Refreshable(
  private val supplier: SempodsCredentialSupplier,
  private val headerName: String,
  private val scheme: String,
) : SempodsRequestAuth {

  private val lock = ReentrantLock()

  @Volatile private var credential: String? = null

  override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
    request.header(headerName, headerValue(acquire(refused = null, attempt)))
  }

  override fun recover(response: Response, attempt: SempodsAuthAttempt): Boolean {
    if (response.code != 401) return false
    val refused = response.request.header(headerName) ?: return false
    acquire(refused, attempt)
    return true
  }

  /** The credential to send: the one held, unless it is what [refused] carried. */
  private fun acquire(refused: String?, attempt: SempodsAuthAttempt): String {
    credential?.let { held -> if (refused == null || headerValue(held) != refused) return held }

    awaitLock(attempt.call)
    try {
      // Another thread may have acquired one while this one waited; that is the coalescing.
      credential?.let { held -> if (refused == null || headerValue(held) != refused) return held }
      return supplier.get(refused != null, attempt).also { credential = it }
    } finally {
      lock.unlock()
    }
  }

  /**
   * Bounded twice: by [call], whose deadline cancels it, and by a floor for a call without a deadline.
   * When a supplier hangs, the operations waiting on it fail.
   */
  private fun awaitLock(call: Call) {
    val giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(CREDENTIAL_WAIT_SECONDS)
    try {
      while (!lock.tryLock(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
        if (call.isCanceled()) throw IOException("Canceled while waiting to acquire a credential.")
        if (System.nanoTime() - giveUpAt > 0) throw IOException("Timed out waiting to acquire a credential.")
      }
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      throw InterruptedIOException("Interrupted while waiting to acquire a credential.")
    }
  }

  private fun headerValue(credential: String): String = if (scheme.isEmpty()) credential else "$scheme $credential"

  private companion object {
    const val CREDENTIAL_WAIT_SECONDS = 30L
    const val POLL_MILLIS = 20L
  }
}
