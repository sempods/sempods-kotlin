package org.sempods.client.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.client.core.net.SempodsRateLimitedException
import org.sempods.client.core.net.SempodsUrlPolicy
import org.sempods.client.core.net.SsrfBlockedException
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * How many operations may be in flight at once, and how many may wait for a turn.
 *
 * **Two numbers, because a queue is not capacity.** Bounding only the active ones lets every
 * further caller pile up behind them, and a slow server then turns into an unbounded queue in this
 * process — memory that grows until something else fails. Over [maxWaiting] a caller is refused
 * immediately, which is an answer it can act on.
 *
 * This bounds [SempodsSession.execute]. A caller that takes a `Call` from
 * [SempodsSession.newCall] and runs it itself has opted out, which is the honest consequence of
 * handing out the engine's own type.
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

/**
 * The resources every session shares: one connection pool, one set of deadlines, one outbound
 * guard, one admission budget.
 *
 * **The engine is OkHttp, and it is not hidden.** [httpClient] is the client this transport
 * configured, and a consumer may take it: add an interceptor, share the connection pool, read a
 * `Response` the way they read every other one. What this type adds is the part OkHttp has no
 * opinion about — the SSRF guard, the pod confinement in [SempodsSession], and a budget for
 * synchronous calls, which OkHttp's dispatcher bounds only for `enqueue`.
 *
 * **The guard is not optional on a client this builds.** A transport created here always carries
 * its `Dns` hook and its per-request check; handing in a client through [Builder.httpClient]
 * decorates that client rather than replacing the guard, so a consumer cannot lose it by supplying
 * their own. What they *can* do is take [httpClient] and call it directly — at which point they
 * have left this library, knowingly.
 *
 * **OkHttp rather than the JDK client** because SSRF resolve-and-pin needs a DNS hook, and the JDK
 * client cannot express one short of replacing the whole JVM's resolver. Blocking on purpose: on
 * Java 25 a blocking send on a virtual thread is what an async client used to buy. See
 * `docs/pod-client.md` §"The transport" for the criterion.
 */
class SempodsTransport private constructor(
  /** The configured engine. Shared by every session on this transport. */
  val httpClient: OkHttpClient,
  internal val admission: SempodsAdmission,
) : AutoCloseable {

  private val active = Semaphore(admission.maxActive)

  private val waiting = Semaphore(admission.maxWaiting)

  /**
   * Takes a slot, waiting no longer than the client's call timeout allows.
   *
   * Refuses immediately when the waiting room is full, because a caller that would have queued
   * behind more than [SempodsAdmission.maxWaiting] others is better told now.
   */
  internal fun admit() {
    // A slot that is free is taken without entering the waiting room at all. Asking for a waiting
    // permit first would make `maxWaiting = 0` mean "no operations", not "no queue".
    if (active.tryAcquire()) return

    if (!waiting.tryAcquire()) {
      throw SempodsClientException(
        "Refused: ${admission.maxActive} operations are in flight and ${admission.maxWaiting} are already waiting.",
      )
    }
    try {
      val budget = httpClient.callTimeoutMillis.toLong()
      val taken =
        if (budget <= 0) active.acquire().let { true }
        else active.tryAcquire(budget, TimeUnit.MILLISECONDS)
      if (!taken) throw SempodsClientException("Timed out waiting for an execution slot.")
    } finally {
      waiting.release()
    }
  }

  internal fun release() {
    active.release()
  }

  /** Closes the connection pool and the dispatcher threads this transport owns. */
  override fun close() {
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
  }

  /** Assembles a transport. Every value has a default; a builder so that adding one is not a break. */
  class Builder internal constructor() {
    private var base: OkHttpClient? = null
    private var guard: SempodsOutboundGuard? = null
    private var admission = SempodsAdmission()
    private var connect = Duration.ofSeconds(10)
    private var read = Duration.ofSeconds(30)
    private var write = Duration.ofSeconds(30)
    private var call = Duration.ofMinutes(2)

    /**
     * A client to derive from, so a consumer's connection pool, cache and interceptors are shared.
     *
     * It is derived, not adopted: the guard, the redirect policy and the deadlines below are
     * applied on top of whatever it carries. A consumer therefore cannot lose the SSRF defence by
     * supplying a client that has none.
     */
    fun httpClient(client: OkHttpClient): Builder = apply { this.base = client }

    /** The outbound guard. Opt-in: a transport without one dials whatever it is given. */
    fun guard(guard: SempodsOutboundGuard?): Builder = apply { this.guard = guard }

    fun admission(admission: SempodsAdmission): Builder = apply { this.admission = admission }

    /**
     * The deadlines. [connect], [read] and [write] bound a single step — the handshake, and the gap
     * between two bytes. [call] bounds the whole call including its body and any authentication
     * retry, and is the only one a peer cannot outlast by answering slowly. [Duration.ZERO] on
     * [call] is the opt-out a long-lived stream needs.
     */
    @JvmOverloads
    fun timeouts(
      connect: Duration = this.connect,
      read: Duration = this.read,
      write: Duration = this.write,
      call: Duration = this.call,
    ): Builder = apply {
      this.connect = connect
      this.read = read
      this.write = write
      this.call = call
    }

    fun build(): SempodsTransport {
      val client = (base?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(connect)
        .readTimeout(read)
        .writeTimeout(write)
        .callTimeout(call)
        // No redirects, on both switches: a followed redirect would reach a host the caller never
        // vetted — while carrying the bearer there.
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
          guard?.let {
            dns(it.dns())
            if (it.proxyless) proxy(Proxy.NO_PROXY)
            addInterceptor(GuardInterceptor(it))
          }
        }
        .build()
      return SempodsTransport(client, admission)
    }
  }

  companion object {

    @JvmStatic
    fun builder(): Builder = Builder()
  }
}

/**
 * The address checks, as an interceptor, so they apply to every call on the client — including one
 * a consumer makes through [SempodsTransport.httpClient] directly.
 *
 * The `Dns` hook alone is half the defence: an engine handed an IP-literal host has nothing to
 * resolve and never asks a resolver, so `http://169.254.169.254/` would walk straight past it. This
 * is the other half, plus the outbound budget.
 */
private class GuardInterceptor(private val guard: SempodsOutboundGuard) : Interceptor {

  override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
    val target = chain.request().url.toUri()
    guard.refuseTarget(target)?.let { refusal ->
      throw when (refusal.kind) {
        SempodsUrlPolicy.Refusal.Kind.SCHEME -> SempodsClientException("Not an HTTP(S) URI: '$target'")
        SempodsUrlPolicy.Refusal.Kind.NO_HOST -> SempodsClientException("URI has no host: '$target'")
        // The cause travels: a blocked address means "this pod URL is wrong", never "this token
        // is dead", and a consumer that tells the two apart does it by walking the cause chain —
        // `PodFailures.isRetryablePodFailure` in `:sempods-mcp` is written that way.
        SempodsUrlPolicy.Refusal.Kind.NON_GLOBAL_HOST -> SempodsClientException(
          "Host '${target.host}' of '$target' is not publicly addressable — ${refusal.detail}",
          SsrfBlockedException(refusal.detail),
        )
      }
    }
    if (!guard.allows(target)) {
      throw SempodsRateLimitedException("outbound rate limit exceeded for $target")
    }
    return chain.proceed(chain.request())
  }
}
