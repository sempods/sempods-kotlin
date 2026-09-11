package org.sempods.client.core

import java.io.IOException
import java.net.URI
import java.util.concurrent.locks.ReentrantLock

/**
 * Supplies a credential, and says whether a refused one is worth re-acquiring.
 *
 * An interface rather than a Kotlin function type, and `String` rather than a parsed token: the
 * core neither knows nor parses a token format. Whoever implements this owns expiry, caching and
 * whatever endpoint mints the value.
 */
fun interface SempodsCredentialSupplier {

  /**
   * The credential to send. [forceRefresh] is `true` when the previous value was refused, so an
   * implementation that caches must not answer from its cache.
   */
  @Throws(IOException::class)
  fun get(forceRefresh: Boolean): String
}

/**
 * The request an authentication mechanism is applied to, and the only thing it may change.
 *
 * **Headers, and nothing else.** No method, no target, no body: authentication that could move a
 * request to another authority would carry the session's credential there, and the outbound guard
 * and the pod confinement check both run before this. Header names are matched case-insensitively.
 */
interface SempodsAuthRequest {

  fun method(): String

  fun uri(): URI

  /** Which attempt this is, from 1. A request-bound header is regenerated for each. */
  fun attempt(): Int

  /**
   * Milliseconds left on the whole-operation deadline, or `-1` when there is none.
   *
   * Exposed because acquiring a credential can block — a refresh that outlives the caller's
   * deadline is a call that has already failed, and an implementation that cannot see the deadline
   * would keep waiting past it.
   */
  fun remainingTimeoutMillis(): Long

  /** Sets [name] to [value], replacing any value already on the request, caller-set or not. */
  fun setHeader(name: String, value: String)

  /** Adds another value for [name]. */
  fun addHeader(name: String, value: String)

  fun removeHeader(name: String)
}

/**
 * What a server said when it refused the credential.
 *
 * Bounded on purpose: the headers a server sends are its own, but the body is not something this
 * client reads unbounded on a failure path — [bodySnippet] stops at
 * [SempodsHttpException.MAX_ERROR_BODY_CHARS].
 */
class SempodsAuthChallenge internal constructor(
  val statusCode: Int,
  val headers: SempodsHeaders,
  val bodySnippet: String?,
  val attempt: Int,
) {
  /** Every `WWW-Authenticate` value, which is the field that may legitimately repeat. */
  fun authenticateHeaders(): List<String> = headers.all("WWW-Authenticate")
}

/**
 * Whether a mechanism believes another attempt would answer differently.
 *
 * **An opinion, not an instruction.** Only the execution layer authorizes a retry, and it refuses
 * one for a non-replayable body, after the body handler has seen data, or once the attempt budget
 * is spent. A mechanism returning [retry] on every challenge therefore cannot loop.
 */
class SempodsAuthRecovery private constructor(val shouldRetry: Boolean) {

  companion object {

    @JvmStatic
    fun none(): SempodsAuthRecovery = NONE

    /** The credential has been invalidated and the next attempt will carry a different one. */
    @JvmStatic
    fun retry(): SempodsAuthRecovery = RETRY

    private val NONE = SempodsAuthRecovery(false)
    private val RETRY = SempodsAuthRecovery(true)
  }
}

/**
 * How a session authenticates its requests — replaceable and decoratable without touching an
 * endpoint, a private internal or a central registration list.
 *
 * **Applied per attempt, after the request is assembled.** A header this sets replaces a
 * same-named header the caller put on the request, and it is recomputed for every attempt — which
 * is the seam a later proof-of-possession mechanism needs, where the header is bound to the
 * request and to a nonce the server just supplied.
 *
 * **Composition.** [andThen] applies the two in declaration order. On a challenge, [recover] is
 * asked in that same order and **the first one to answer [SempodsAuthRecovery.retry] wins**; the
 * rest are not asked. However long the chain, one operation gets at most one extra attempt.
 *
 * **Concurrency.** An instance is shared by every call of its session and must be safe for
 * concurrent use. [refreshable] coalesces: concurrent callers that find the credential refused make
 * one acquisition between them, and a session with a different credential is not held up by it.
 */
fun interface SempodsRequestAuth {

  @Throws(IOException::class)
  fun apply(request: SempodsAuthRequest)

  /** Whether another attempt is worth making. Nothing by default — see [SempodsAuthRecovery]. */
  fun recover(challenge: SempodsAuthChallenge): SempodsAuthRecovery = SempodsAuthRecovery.none()

  fun andThen(next: SempodsRequestAuth): SempodsRequestAuth = Composite(listOf(this, next))

  private class Composite(val members: List<SempodsRequestAuth>) : SempodsRequestAuth {

    override fun apply(request: SempodsAuthRequest) = members.forEach { it.apply(request) }

    override fun recover(challenge: SempodsAuthChallenge): SempodsAuthRecovery =
      if (members.any { it.recover(challenge).shouldRetry }) SempodsAuthRecovery.retry()
      else SempodsAuthRecovery.none()

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
     */
    @JvmStatic
    fun anonymous(): SempodsRequestAuth = SempodsRequestAuth { }

    /**
     * A credential the caller already holds.
     *
     * A 401 is not retried for the same reason as [anonymous]: re-sending exactly what the server
     * just refused pays for the failure twice.
     */
    @JvmStatic
    fun bearer(token: String): SempodsRequestAuth =
      SempodsRequestAuth { it.setHeader("Authorization", "Bearer $token") }

    /** A fixed value in a header of the deployment's choosing. Not retried, as [bearer]. */
    @JvmStatic
    fun apiKeyHeader(name: String, value: String): SempodsRequestAuth =
      SempodsRequestAuth { it.setHeader(name, value) }

    /**
     * A bearer that can be re-acquired, and the only convenience that retries.
     *
     * On a 401 the credential is dropped and one further attempt is made with a freshly supplied
     * one. A refresh margin narrows the expiry race but cannot close it, because a token can be
     * rotated or revoked mid-flight — which is why recovery exists at all rather than expiry
     * handling alone.
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
 * **Why a generation counter rather than a flag.** Two threads whose requests are refused at the
 * same moment must produce one acquisition, not two, and the second must not then throw away the
 * value the first just obtained. Each holder remembers the generation it read; a thread asking to
 * replace generation *n* finds the work already done when the counter has moved on, and uses that
 * result.
 *
 * The lock is this object's, so it is per credential: a session authenticating against another pod
 * shares none of it and is never held up. Waiting for it is bounded by the caller's remaining
 * deadline, so a supplier that hangs fails the operation that was going to fail anyway rather than
 * every operation behind it.
 */
private class Refreshable(
  private val supplier: SempodsCredentialSupplier,
  private val headerName: String,
  private val scheme: String,
) : SempodsRequestAuth {

  private val lock = ReentrantLock()

  @Volatile private var credential: String? = null

  @Volatile private var generation: Long = 0

  /** The generation each in-flight attempt authenticated with, so recovery replaces the right one. */
  private val attemptGeneration = ThreadLocal<Long>()

  override fun apply(request: SempodsAuthRequest) {
    val value = acquire(replacing = null, remainingMillis = request.remainingTimeoutMillis())
    attemptGeneration.set(generation)
    request.setHeader(headerName, if (scheme.isEmpty()) value else "$scheme $value")
  }

  override fun recover(challenge: SempodsAuthChallenge): SempodsAuthRecovery {
    if (challenge.statusCode != 401) return SempodsAuthRecovery.none()
    val refused = attemptGeneration.get() ?: return SempodsAuthRecovery.none()
    return runCatching { acquire(replacing = refused, remainingMillis = -1) }
      .fold({ SempodsAuthRecovery.retry() }, { SempodsAuthRecovery.none() })
  }

  private fun acquire(replacing: Long?, remainingMillis: Long): String {
    val held = credential
    if (held != null && (replacing == null || generation != replacing)) return held

    val locked =
      if (remainingMillis < 0) lock.lock().let { true }
      else lock.tryLock(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!locked) {
      throw SempodsTransportException("Timed out waiting to acquire a credential.")
    }
    try {
      // Another thread may have acquired one while this one waited; that is the coalescing.
      val now = credential
      if (now != null && (replacing == null || generation != replacing)) return now
      val fresh = supplier.get(replacing != null)
      credential = fresh
      generation += 1
      return fresh
    } catch (e: IOException) {
      throw SempodsTransportException("Could not acquire a credential: ${e.message}", e)
    } finally {
      lock.unlock()
    }
  }
}
