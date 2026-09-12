package org.sempods.client.core

import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Supplies a credential, and says whether a refused one is worth re-acquiring.
 *
 * A `String` rather than a parsed token: the core neither knows nor parses a token format. Whoever
 * implements this owns expiry, caching and whatever endpoint mints the value.
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
 * How a session authenticates its requests — replaceable and decoratable without touching an
 * endpoint, a private internal or a central registration list.
 *
 * **Applied per attempt, on the request that is about to go out.** [apply] receives the builder, so
 * a header it sets replaces a same-named header the caller put on the request, and it is recomputed
 * for every attempt — which is the seam a later proof-of-possession mechanism needs, where the
 * header is bound to the request and to a nonce the server just supplied.
 *
 * **Headers, and nothing else.** A mechanism that changed `url` would carry the session's
 * credential to another authority; the session checks the target again afterwards and refuses the
 * call rather than sending it. The builder is OkHttp's because this library has no reason to own a
 * second one — the restriction is enforced, not typed away.
 *
 * **Composition.** [andThen] applies the two in declaration order. On a challenge, [recover] is
 * asked in that same order and the first one to answer `true` wins; the rest are not asked.
 * However long the chain, one operation gets at most one extra attempt.
 *
 * **Concurrency.** An instance is shared by every call of its session and must be safe for
 * concurrent use. [refreshable] coalesces: concurrent callers that find the credential refused make
 * one acquisition between them, and a session with a different credential is not held up by it.
 */
fun interface SempodsRequestAuth {

  /** Puts this mechanism's headers on [request], which is about to be sent as attempt [attempt]. */
  @Throws(IOException::class)
  fun apply(request: Request.Builder, attempt: Int)

  /**
   * Whether another attempt would answer differently, having seen the refusal.
   *
   * **An opinion, not an instruction.** Only the execution layer authorizes a retry, and it refuses
   * one for a non-replayable body or once the attempt budget is spent — so a mechanism returning
   * `true` on every challenge cannot loop. [response] is the refusal itself, headers included; its
   * body has not been read and must not be consumed here.
   */
  @Throws(IOException::class)
  fun recover(response: Response, attempt: Int): Boolean = false

  fun andThen(next: SempodsRequestAuth): SempodsRequestAuth = Composite(listOf(this, next))

  private class Composite(val members: List<SempodsRequestAuth>) : SempodsRequestAuth {

    override fun apply(request: Request.Builder, attempt: Int) =
      members.forEach { it.apply(request, attempt) }

    override fun recover(response: Response, attempt: Int): Boolean =
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
     */
    @JvmStatic
    fun anonymous(): SempodsRequestAuth = SempodsRequestAuth { _, _ -> }

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
 * shares none of it and is never held up.
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

  override fun apply(request: Request.Builder, attempt: Int) {
    val value = acquire(replacing = null)
    attemptGeneration.set(generation)
    request.header(headerName, if (scheme.isEmpty()) value else "$scheme $value")
  }

  override fun recover(response: Response, attempt: Int): Boolean {
    if (response.code != 401) return false
    val refused = attemptGeneration.get() ?: return false
    return runCatching { acquire(replacing = refused) }.isSuccess
  }

  private fun acquire(replacing: Long?): String {
    val held = credential
    if (held != null && (replacing == null || generation != replacing)) return held

    // Bounded, so a supplier that hangs fails the operation that was going to fail anyway rather
    // than every operation behind it. The whole-call deadline is the engine's; this is the floor.
    if (!lock.tryLock(CREDENTIAL_WAIT_SECONDS, TimeUnit.SECONDS)) {
      throw IOException("Timed out waiting to acquire a credential.")
    }
    try {
      // Another thread may have acquired one while this one waited; that is the coalescing.
      val now = credential
      if (now != null && (replacing == null || generation != replacing)) return now
      val fresh = supplier.get(replacing != null)
      credential = fresh
      generation += 1
      return fresh
    } finally {
      lock.unlock()
    }
  }

  private companion object {
    const val CREDENTIAL_WAIT_SECONDS = 30L
  }
}
