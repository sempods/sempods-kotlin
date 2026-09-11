package org.sempods.client.core

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.client.core.net.SempodsRateLimitedException
import org.sempods.client.core.net.SempodsUrlPolicy
import org.sempods.client.core.net.SsrfBlockedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The deadlines every call inherits. Four knobs rather than one because they answer different
 * questions, and collapsing them is how a streaming read acquires a deadline it must not have:
 *
 * - [connect] / [read] / [write] bound a *single* step — the TCP+TLS handshake, and the gap between
 *   two bytes in either direction. A body that keeps arriving keeps the read timeout at bay.
 * - [operation] bounds the **whole** operation: waiting for admission, acquiring and refreshing a
 *   credential, every attempt, and the body handler. It is the only one a peer cannot outlast by
 *   answering slowly, and it is wider than the engine's own call timeout, which knows about one
 *   attempt and nothing before or after it.
 *
 * [Duration.ZERO] on [operation] means no whole-operation deadline. That is a caller's choice for a
 * genuinely unbounded read, not a default: an operation that may legitimately outlast two minutes
 * says so at its own call site with [SempodsRequest.Builder.operationTimeout].
 */
data class SempodsHttpTimeouts @JvmOverloads constructor(
  val connect: Duration = Duration.ofSeconds(10),
  val read: Duration = Duration.ofSeconds(30),
  val write: Duration = Duration.ofSeconds(30),
  val operation: Duration = Duration.ofMinutes(2),
)

/**
 * How many operations may be in flight at once, and how many may wait for a turn.
 *
 * **Two numbers, because a queue is not capacity.** Bounding only the active ones lets every
 * further caller pile up behind them, and a slow server then turns into an unbounded queue in this
 * process — memory that grows until something else fails. Over [maxWaiting] a caller is refused
 * immediately, which is an answer it can act on.
 *
 * **A streamed body counts as active until it is closed.** The bytes are still arriving and the
 * connection is still held; releasing the slot when the status line arrives would let an unbounded
 * number of half-read responses exist under a limit that says otherwise.
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
 * **Shared on purpose.** Every `OkHttpClient` owns a dispatcher thread pool and a pool of idle
 * connections; minting one per session leaks both. Two sessions against different pods take one
 * transport and keep their own credentials — that separation is [SempodsSession]'s, not this
 * object's.
 *
 * **The engine stops here — including in this class's own signature.** Callers speak
 * [SempodsRequest] / [SempodsResponse] / [SempodsBody] and name no HTTP library; see [SempodsBody]
 * for why that boundary is drawn at the transport rather than left to the build file. There is
 * deliberately no way to hand in an engine: a constructor taking one would put `okhttp3` on a
 * consumer's compile classpath — which `implementation` says it is not on — and tie this library's
 * ABI to the engine's major version.
 *
 * **OkHttp rather than the JDK client** because SSRF resolve-and-pin needs a DNS hook, and the JDK
 * client cannot express one short of replacing the whole JVM's resolver. Blocking on purpose: on
 * Java 25 a blocking send on a virtual thread is what an async client used to buy. See
 * `docs/pod-client.md` §"The transport" for the criterion.
 *
 * **What [close] closes is what this object owns.** The connection pool and the idle dispatcher
 * threads of the variant built here; never the process-wide pool it derives from, and never a
 * caller's own executor, of which it holds none — the blocking entry point runs on its caller's
 * thread.
 */
class SempodsTransport private constructor(
  private val timeouts: SempodsHttpTimeouts,
  private val guard: SempodsOutboundGuard?,
  internal val admission: SempodsAdmission,
) : AutoCloseable {

  private val httpClient: OkHttpClient = SHARED.newBuilder()
    .connectTimeout(timeouts.connect)
    .readTimeout(timeouts.read)
    .writeTimeout(timeouts.write)
    // The engine's own retry is switched off so that this library has one retry policy instead of
    // two. OkHttp retransmits a request when a pooled connection turns out to be stale, which is
    // useful — but it happens below the execution layer, so the attempt is not counted, the
    // deadline is not re-checked, and a request-bound authentication header is *not* regenerated.
    // For a proof-of-possession mechanism that binds a header to a nonce, replaying the first
    // attempt's header is simply wrong. `SempodsExecution` makes the same repeat itself, where it
    // is visible.
    .retryOnConnectionFailure(false)
    .apply {
      guard?.let {
        dns(it.dns())
        if (it.proxyless) proxy(Proxy.NO_PROXY)
        if (it.rateLimiter != null) addInterceptor(rateLimitInterceptor(it))
      }
    }
    .build()

  /**
   * Variants for requests that override the whole-operation deadline. Cached because a per-request
   * `newBuilder()` would allocate on every call; each variant still shares the pool and dispatcher.
   */
  private val byCallTimeout = ConcurrentHashMap<Duration, OkHttpClient>()

  private val active = Semaphore(admission.maxActive)

  private val waiting = Semaphore(admission.maxWaiting)

  internal val defaultOperationTimeout: Duration get() = timeouts.operation

  /**
   * Takes a slot, waiting no longer than [deadline] allows.
   *
   * Refuses immediately when the waiting room is full, because a caller that would have queued
   * behind more than [SempodsAdmission.maxWaiting] others is better told now.
   */
  internal fun admit(deadline: SempodsDeadline) {
    // A slot that is free is taken without entering the waiting room at all. Asking for a waiting
    // permit first would make `maxWaiting = 0` mean "no operations", not "no queue".
    if (active.tryAcquire()) return

    if (!waiting.tryAcquire()) {
      throw SempodsTransportException(
        "Refused: ${admission.maxActive} operations are in flight and ${admission.maxWaiting} are already waiting.",
      )
    }
    try {
      val remaining = deadline.remainingMillis()
      val taken =
        if (remaining < 0) active.acquire().let { true }
        else active.tryAcquire(remaining, TimeUnit.MILLISECONDS)
      if (!taken) throw SempodsTransportException("Timed out waiting for an execution slot.")
    } finally {
      waiting.release()
    }
  }

  internal fun release() {
    active.release()
  }

  internal fun guardTarget(uri: URI) {
    val refusal = guard?.refuseTarget(uri) ?: return
    throw when (refusal.kind) {
      SempodsUrlPolicy.Refusal.Kind.SCHEME -> SempodsTransportException("Not an HTTP(S) URI: '$uri'")
      SempodsUrlPolicy.Refusal.Kind.NO_HOST -> SempodsTransportException("URI has no host: '$uri'")
      SempodsUrlPolicy.Refusal.Kind.NON_GLOBAL_HOST ->
        notPubliclyAddressable(uri, refusal.detail, SsrfBlockedException(refusal.detail))
    }
  }

  /**
   * Sends one attempt and hands its still-open body to [read].
   *
   * [operation] is bound to the engine's call for the duration, so a cancel arriving mid-read
   * closes the socket rather than waiting for the peer.
   */
  internal fun <T> attempt(
    uri: URI,
    method: String,
    headers: List<Pair<String, String>>,
    body: SempodsBody?,
    callTimeout: Duration?,
    operation: SempodsOperation,
    read: (SempodsStreamedResponse) -> T,
  ): T {
    operation.failIfCancelled()
    val call = clientFor(callTimeout).newCall(toOkHttpRequest(uri, method, headers, body))
    operation.bind(call::cancel)
    return try {
      call.execute().use { response ->
        read(SempodsStreamedResponse(response.code, toHeaders(response.headers), response.body.byteStream()))
      }
    } catch (e: SsrfBlockedException) {
      throw notPubliclyAddressable(uri, e.message ?: "blocked address", e)
    } catch (e: InterruptedIOException) {
      if (operation.isCancelled) throw SempodsTransportException("The operation was cancelled.", e)
      throw SempodsTransportException("$method $uri timed out: ${e.message}", e)
    } catch (e: IOException) {
      if (operation.isCancelled) throw SempodsTransportException("The operation was cancelled.", e)
      throw SempodsTransportException("$method $uri failed: ${e.message}", e)
    } catch (e: SempodsRateLimitedException) {
      throw SempodsTransportException(e.message ?: "outbound rate limit exceeded", e)
    } finally {
      operation.unbind()
    }
  }

  /**
   * Runs [request] against its absolute target, authenticated by [auth] alone.
   *
   * **Nothing is inherited here.** This is the path for a target that is not under a pod — a
   * foreign URI being dereferenced, a token endpoint — and it holds no session, so there is no
   * credential for it to reach for. Whatever [auth] is given applies to this one operation and this
   * one target; the default is [SempodsRequestAuth.anonymous], because dereferencing a stranger's
   * URI with a pod's bearer is how a credential leaves the pod it belongs to.
   *
   * The outbound guard, the deadline, admission, cancellation and the body lifetime apply exactly
   * as they do to a session's request.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun <T> execute(
    request: SempodsRequest,
    handler: SempodsBodyHandler<T>,
    auth: SempodsRequestAuth = SempodsRequestAuth.anonymous(),
    operation: SempodsOperation = SempodsOperation.current() ?: SempodsOperation(),
  ): SempodsResponse<T> = SempodsExecution(this, auth, timeouts.operation).run(request, handler, operation)

  /** Closes the connection pool and the dispatcher threads this transport owns. */
  override fun close() {
    httpClient.dispatcher.executorService.shutdown()
    httpClient.connectionPool.evictAll()
    byCallTimeout.values.forEach { it.connectionPool.evictAll() }
  }

  /**
   * One wording for both address layers, and the cause travels with it. The cause is what a caller
   * classifies on: a blocked address means "this pod URL is wrong", never "this token is dead", and
   * an exception that dropped its cause cannot tell the two apart.
   */
  private fun notPubliclyAddressable(uri: URI, reason: String, cause: Throwable) =
    SempodsTransportException("Host '${uri.host}' of '$uri' is not publicly addressable — $reason", cause)

  private fun rateLimitInterceptor(guard: SempodsOutboundGuard) = Interceptor { chain ->
    val target = chain.request().url.toUri()
    if (!guard.allows(target)) {
      throw SempodsRateLimitedException("outbound rate limit exceeded for $target")
    }
    chain.proceed(chain.request())
  }

  private fun clientFor(callTimeout: Duration?): OkHttpClient =
    if (callTimeout == null) httpClient
    else byCallTimeout.computeIfAbsent(callTimeout) { httpClient.newBuilder().callTimeout(it).build() }

  private fun toOkHttpRequest(
    uri: URI,
    method: String,
    headers: List<Pair<String, String>>,
    body: SempodsBody?,
  ): Request {
    val builder = Request.Builder().url(uri.toString())
    // `addHeader` rather than `header`: replacement is decided on `SempodsRequest.Builder`, where a
    // caller can see it, and a list that reached here is a list the caller meant to send.
    headers.forEach { (name, value) -> builder.addHeader(name, value) }
    return builder.method(method, requestBody(method, body)).build()
  }

  /**
   * `null` for the methods that must not carry a body, an empty body otherwise — so a DELETE still
   * goes out with `Content-Length: 0`.
   */
  private fun requestBody(method: String, body: SempodsBody?): RequestBody? = when {
    body != null -> toRequestBody(body)
    method == "GET" || method == "HEAD" || method == "OPTIONS" -> null
    else -> EMPTY_BODY
  }

  /**
   * No body carries a content type. `Content-Type` is a header the call sites set explicitly, and a
   * body that also declared one would win over them — OkHttp's bridge overwrites the header from
   * `RequestBody.contentType()` whenever that is non-null. Keeping it null is also what stops a text
   * body from acquiring a `; charset=utf-8` suffix the pod's exact media types do not expect.
   */
  private fun toRequestBody(body: SempodsBody): RequestBody = when (body) {
    is SempodsBody.Empty -> EMPTY_BODY
    is SempodsBody.Bytes -> body.value.toRequestBody(null)
    is SempodsBody.Stream -> SuppliedStreamBody(body.size, body.source, body.replayable)
  }

  private fun toHeaders(headers: Headers): SempodsHeaders =
    SempodsHeaders((0 until headers.size).map { headers.name(it) to headers.value(it) })

  /**
   * A body the engine writes from a caller's source.
   *
   * `isOneShot` is the caller's own answer, carried through from [SempodsBody.oneShotStream].
   * Saying `false` about a stream that cannot be re-read is what turns a repeat into a silent
   * upload of zero bytes, so the default is whatever the body was built as rather than whatever is
   * convenient.
   */
  private class SuppliedStreamBody(
    private val size: Long?,
    private val source: SempodsBodySource,
    private val replayable: Boolean,
  ) : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = size ?: -1L
    override fun isOneShot(): Boolean = !replayable
    override fun writeTo(sink: BufferedSink) {
      source.open().source().use { sink.writeAll(it) }
    }
  }

  /** Assembles a transport. Every value has a default; a builder so that adding one is not a break. */
  class Builder internal constructor() {
    private var timeouts = SempodsHttpTimeouts()
    private var guard: SempodsOutboundGuard? = null
    private var admission = SempodsAdmission()

    fun timeouts(timeouts: SempodsHttpTimeouts): Builder = apply { this.timeouts = timeouts }

    /** The outbound guard, or none. Opt-in: a transport without one dials whatever it is given. */
    fun guard(guard: SempodsOutboundGuard?): Builder = apply { this.guard = guard }

    fun admission(admission: SempodsAdmission): Builder = apply { this.admission = admission }

    fun build(): SempodsTransport = SempodsTransport(timeouts, guard, admission)
  }

  companion object {

    @JvmStatic
    fun builder(): Builder = Builder()

    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

    /**
     * The one client the whole process shares — see the note on the connection pool above. Timeouts
     * are not set here: a transport derives its own through `newBuilder()`, which keeps this pool
     * and this dispatcher. Private, so the engine type does not reach a consumer through a factory
     * either.
     *
     * No redirects, on both switches: a followed redirect would reach a host the caller never
     * vetted — while carrying the bearer there.
     */
    private val SHARED: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    }
  }
}
