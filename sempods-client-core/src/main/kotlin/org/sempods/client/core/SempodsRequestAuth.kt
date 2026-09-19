package org.sempods.client.core

import okhttp3.Call
import okhttp3.Credentials
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * [request] with this mechanism applied for [attempt]; refused when it changed more than headers
 * ([SempodsRequestAuth] says why). The refusal names the URL without its query, where a token may be.
 */
@Throws(IOException::class)
@JvmSynthetic
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
 * header is bound to the request and to a nonce the server just supplied. [observe] is where that
 * nonce arrives, and [recover] where a refusal is claimed.
 *
 * **Headers, and nothing else.** A mechanism that changed the URL would carry the session's
 * credential to another authority, and one that changed the method or the body would send a request
 * the caller never built. The client's session interceptor compares all three after [apply] and
 * refuses the call.
 *
 * **Composition.** [andThen] applies the two in declaration order and tells both about every
 * answer; one whose [observe] fails does not keep the other from being told, and the first failure
 * fails the call. On a refusal, [recover] is asked in that same order and the first one to answer `true`
 * wins; the rest are not asked. A mechanism claims the challenge that names it, so a bearer
 * refresher and a nonce adapter each answer their own 401 whichever way round they are declared;
 * where both claim one challenge, declaration order decides. However long the chain, one operation
 * gets at most one authentication retry.
 *
 * **Concurrency.** An instance is shared by every call of its session and must be safe for
 * concurrent use. [refreshable] coalesces: while one acquisition is in flight, every caller refused
 * for the credential it replaces takes its result, whether or not the value changed. A refusal that
 * reaches recovery after that acquisition finished acquires again, because what a mechanism is told
 * is the credential a request carried and never the acquisition it came from. A session with a
 * different credential is held up by none of it.
 */
fun interface SempodsRequestAuth {

  /** Puts this mechanism's headers on [request], which is about to be sent as [attempt]. */
  @Throws(IOException::class)
  fun apply(request: Request.Builder, attempt: SempodsAuthAttempt)

  /**
   * Told about [facts], the answer to [attempt] — every answer, a 2xx included, before the caller
   * sees it.
   *
   * **This is where a mechanism keeps what the server just said**, such as a `DPoP-Nonce` to send
   * next time. It runs whether or not another attempt is possible, so a body that can be written
   * once and a spent attempt budget hide nothing a later call needs.
   *
   * **A failure fails the call**, after closing the answer. The core logs nothing, so a mechanism
   * whose bookkeeping may fail without consequence catches its own.
   *
   * One call per attempt. What OkHttp does below the session's interceptor is not seen: a `421`
   * repeated over a coalesced HTTP/2 connection, and a redirect followed by a consumer who turned
   * `followRedirects` back on after `SempodsOkHttp.install`
   * ([#160](https://github.com/sempods/sempods-kotlin/issues/160)).
   */
  @Throws(IOException::class)
  fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) = Unit

  /**
   * Whether another attempt would answer differently, having seen a refusal — an answer outside 2xx.
   *
   * **Asked only when another attempt is possible.** A 2xx is never repeated, and a body that can
   * be written once or a spent attempt budget rules a repeat out before this is asked. What the
   * mechanism is told regardless is [observe]'s.
   *
   * **An opinion, not an instruction.** Only the execution layer authorizes a retry, so a mechanism
   * that answers `true` to every challenge cannot loop.
   */
  @Throws(IOException::class)
  fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean = false

  fun andThen(next: SempodsRequestAuth): SempodsRequestAuth = Composite(listOf(this, next))

  private class Composite(val members: List<SempodsRequestAuth>) : SempodsRequestAuth {

    override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) =
      members.forEach { it.apply(request, attempt) }

    /** Every member is told before a failure is raised: what a later one keeps is for the next call. */
    override fun observe(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt) {
      var failure: Throwable? = null
      for (member in members) {
        try {
          member.observe(facts, attempt)
        } catch (thrown: Throwable) {
          if (failure == null) failure = thrown else failure.addSuppressed(thrown)
        }
      }
      failure?.let { throw it }
    }

    override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean =
      members.any { it.recover(facts, attempt) }

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
     * An OAuth client's own credential, as `client_secret_basic`: what a [SempodsPodTokens] session
     * authenticates with. Not retried, as [bearer].
     *
     * RFC 6749 §2.3.1 form-encodes both values before they are joined and Base64-encoded. With the secret
     * `a+b`, the header encodes `notes-app:a%2Bb`. Unencoded, a server that follows the RFC reads the
     * secret as `a b` and refuses it.
     */
    @JvmStatic
    fun clientSecretBasic(clientId: String, clientSecret: String): SempodsRequestAuth {
      val value = Credentials.basic(formEncoded(clientId), formEncoded(clientSecret), Charsets.UTF_8)
      return SempodsRequestAuth { request, _ -> request.header("Authorization", value) }
    }

    private fun formEncoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /**
     * A bearer that can be re-acquired, and the only convenience that retries.
     *
     * On a 401 it claims the refusal, drops the credential and makes one further attempt with a
     * freshly supplied one; a supplier that fails fails the call with its exception. A refresh
     * margin narrows the expiry race but cannot close it, because a token can be rotated or revoked
     * mid-flight — which is why recovery exists at all rather than expiry handling alone.
     *
     * **It claims a 401 that names [scheme], and one that carries no challenge at all.** A
     * `WWW-Authenticate: DPoP error="use_dpop_nonce"` belongs to whatever answers a nonce, and this
     * bearer leaves a token that is still valid alone. An empty [scheme] — an API key in a header of
     * its own — is named by no challenge, so a challenged 401 is another mechanism's.
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
 * The refreshable bearer, and how the coalescing [SempodsRequestAuth] promises is kept.
 *
 * **Every acquisition raises [generation], and a caller reads it before the credential.** One that
 * completed since that read shows as a moved generation, so callers refused together share a single
 * acquisition even where the supplier answered with the same value. The value comparison beside it
 * is the fast path, for a caller whose credential another thread has already replaced.
 *
 * The read happens when recovery starts, which is as close to the refusal as the header gets: it
 * names the credential a request carried and not the acquisition that produced it. A caller whose
 * refusal reaches recovery after the wave's acquisition finished therefore acquires once more, where
 * the supplier answered with the same value. Recognising it would take the generation of the attempt
 * that was refused, which no hook carries today.
 *
 * The lock is this object's, so it is per credential: a session authenticating against another pod
 * shares none of it.
 */
private class Refreshable(
  private val supplier: SempodsCredentialSupplier,
  private val headerName: String,
  private val scheme: String,
) : SempodsRequestAuth {

  private val lock = ReentrantLock()

  @Volatile private var credential: String? = null

  /** Acquisitions completed. Written under [lock], read without it. */
  @Volatile private var generation: Long = 0

  override fun apply(request: Request.Builder, attempt: SempodsAuthAttempt) {
    request.header(headerName, headerValue(acquire(refused = null, attempt)))
  }

  override fun recover(facts: SempodsResponseFacts, attempt: SempodsAuthAttempt): Boolean {
    if (facts.status != 401 || !claims(facts)) return false
    val refused = facts.sentHeaders[headerName] ?: return false
    acquire(refused, attempt)
    return true
  }

  /**
   * Whether this refusal is of this mechanism's credential: a challenge naming [scheme], or no
   * challenge at all, which names nothing and may as well be its own.
   */
  private fun claims(facts: SempodsResponseFacts): Boolean =
    facts.challenges.isEmpty() ||
      scheme.isNotEmpty() && facts.challenges.any { it.scheme.equals(scheme, ignoreCase = true) }

  /** The credential to send: the one held, unless it is what [refused] carried and none has landed since. */
  private fun acquire(refused: String?, attempt: SempodsAuthAttempt): String {
    // Read before the credential: an acquisition overlapping this one then shows as a moved
    // generation, and the pair is at worst conservative.
    val seen = generation
    credential?.let { held -> if (usable(held, refused, seen)) return held }

    awaitLock(attempt.call)
    try {
      // Another thread may have acquired one while this one waited; that is the coalescing.
      credential?.let { held -> if (usable(held, refused, seen)) return held }
      // The lock can come free just after the call was cancelled, which the wait checks only between polls.
      if (attempt.call.isCanceled()) throw IOException("Canceled while waiting to acquire a credential.")
      return supplier.get(refused != null, attempt).also {
        credential = it
        generation++
      }
    } finally {
      lock.unlock()
    }
  }

  /** Whether [held] is worth sending: it is not what [refused] carried, or an acquisition landed since [seen]. */
  private fun usable(held: String, refused: String?, seen: Long): Boolean =
    refused == null || headerValue(held) != refused || generation != seen

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
