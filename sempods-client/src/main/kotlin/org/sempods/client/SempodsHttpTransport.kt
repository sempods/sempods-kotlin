package org.sempods.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsRateLimitedException
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.client.net.SsrfBlockedException
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.io.InputStream
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The deadlines every call inherits. Four knobs rather than one because they answer different
 * questions, and collapsing them is how a streaming read acquires a deadline it must not have:
 *
 * - [connect] / [read] / [write] bound a *single* step — the TCP+TLS handshake, and the gap between
 *   two bytes in either direction. A body that keeps arriving keeps the read timeout at bay.
 * - [call] bounds the **whole** call including the body. It is the only one a peer cannot outlast
 *   by answering slowly: [read] measures the gap between two bytes, so a drip just inside it runs
 *   forever. Two minutes by default, which is what the JDK client's request timeout bounded before
 *   this transport existed — that timeout covered the body too, contrary to what an earlier
 *   revision of this comment claimed.
 *
 * [Duration.ZERO] on [call] means no whole-call deadline. That is a caller's choice for a genuinely
 * unbounded read, not a default: an operation that may legitimately outlast two minutes says so at
 * its own call site with [SempodsRequest.Builder.callTimeout].
 */
data class SempodsHttpTimeouts(
  val connect: Duration = Duration.ofSeconds(10),
  val read: Duration = Duration.ofSeconds(30),
  val write: Duration = Duration.ofSeconds(30),
  val call: Duration = Duration.ofMinutes(2),
)

/**
 * What every call against a sempods deployment shares: how a request is built, how it is sent, and
 * how a refused one becomes an exception.
 *
 * **Public because Kotlin's `internal` stops at the module boundary, not because it is an API to
 * program against.** Its consumers are the two clients — [SempodsClient] for the pod surface,
 * `SempodsControlPlaneClient` (`:sempods-control-plane-client`) for the host-level admin surface —
 * and it exists so that the two do not each carry their own copy of the decisions below. Each is a
 * decision a duplicate would silently get wrong:
 *
 * - **The trace binding.** A request built without [newRequest] ends the caller's trace, and
 *   nothing downstream reports the gap.
 * - **No redirects**, on both switches. [SempodsClient.dereference] takes URIs that came from a
 *   request, and a followed redirect would reach a host the caller never vetted — while carrying
 *   the bearer there.
 * - **One connection pool.** Every `OkHttpClient` owns a dispatcher thread pool and a pool of idle
 *   connections; minting one per client object leaks both. Everything here shares [sharedHttpClient]
 *   and varies it through `newBuilder()`, which keeps the pool and the dispatcher.
 *
 * **The engine stops here — including in this class's own signature.** Callers speak
 * [SempodsRequest] / [SempodsResponse] / [SempodsBody] and name no HTTP library; see [SempodsBody]
 * for why that boundary is drawn at the transport rather than left to the build file. There is
 * deliberately no way to hand in an engine: a constructor taking one would put `okhttp3` on a
 * consumer's compile classpath — which `implementation` says it is not on — and tie this library's
 * ABI to the engine's major version. If injecting one is ever needed, it arrives as an
 * engine-neutral seam rather than as the engine.
 *
 * **OkHttp rather than the JDK client** because SSRF resolve-and-pin needs a DNS hook, and the JDK
 * client cannot express one short of replacing the whole JVM's resolver. Blocking on purpose: on
 * Java 25 a blocking send on a virtual thread is what an async client used to buy. See
 * `docs/pod-client.md` §"The transport" for the criterion.
 */
class SempodsHttpTransport(
  timeouts: SempodsHttpTimeouts = SempodsHttpTimeouts(),
  private val guard: SempodsOutboundGuard? = null,
) {

  private val httpClient: OkHttpClient = SHARED.newBuilder()
    .connectTimeout(timeouts.connect)
    .readTimeout(timeouts.read)
    .writeTimeout(timeouts.write)
    .callTimeout(timeouts.call)
    .apply {
      guard?.let {
        dns(it.dns())
        if (it.proxyless) proxy(Proxy.NO_PROXY)
        if (it.rateLimiter != null) addInterceptor(rateLimitInterceptor(it))
      }
    }
    .build()

  /**
   * Variants for requests that override the whole-call deadline. Cached because a per-request
   * `newBuilder()` would allocate on every call; each variant still shares the pool and dispatcher.
   */
  private val byCallTimeout = ConcurrentHashMap<Duration, OkHttpClient>()

  /** Shared by both clients so a response is parsed the same way wherever it is read. */
  val objectMapper: ObjectMapper = ObjectMapper()

  /**
   * Starts every request either client sends: the bearer when there is one, and the caller's W3C
   * trace when one is bound. Each request gets its own span id; the trace id is what carries.
   *
   * **[token] is nullable on purpose.** A pod serves its public contexts to an unauthenticated
   * caller (`SempodsBaseEndpoint.authenticate` treats a missing bearer as anonymous), and reading
   * public data is the first thing anyone does with a pod they do not own. Omitting the header is
   * therefore a supported mode, not a degraded one; what a caller may
   * do without a token is the server's decision, expressed as a 401 or a filtered answer.
   *
   * Outside a request no trace is bound and no trace header is set.
   */
  // TODO: an explicit `traceparent` from a caller is *appended* here, not substituted — the builder
  //   keeps a list and `toOkHttpRequest` calls `addHeader` per entry, so a caller setting the header
  //   after `newRequest` puts two on the wire. The two interceptor paths (`TraceparentInterceptor`,
  //   `TraceparentClientPlugin`) leave an existing header alone, which is the behaviour
  //   `docs/request-tracing.md` describes; this path should match it. Needs replacement semantics on
  //   `SempodsRequest.Builder.header` and a test. No caller in the tree does it today.
  fun newRequest(uri: URI, token: String? = null): SempodsRequest.Builder {
    // `null` call timeout = whatever the transport was configured with; only an explicit
    // `callTimeout(…)` on the builder departs from it.
    val builder = SempodsRequest.Builder(uri, null)
    token?.let { builder.header("Authorization", "Bearer $it") }
    TraceContextHolder.get()?.let { traceContext ->
      builder.header(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
    }
    return builder
  }

  fun send(request: SempodsRequest): SempodsResponse<String> =
    execute(request) { SempodsResponse(it.code, it.body.string(), headerLookup(it.headers)) }

  fun sendBytes(request: SempodsRequest): SempodsResponse<ByteArray> =
    execute(request) { SempodsResponse(it.code, it.body.bytes(), headerLookup(it.headers)) }

  /**
   * Streams a response body through [read] and closes it afterwards, whatever [read] does.
   *
   * Scoped rather than returned so the body cannot outlive the call that owns it: an unclosed
   * response strands a pooled connection, and a signature that hands one out makes every call site
   * responsible for remembering. The failure path is served by
   * [SempodsStreamedResponse.bodyTextCapped].
   */
  fun <T> sendStreaming(request: SempodsRequest, read: (SempodsStreamedResponse) -> T): T =
    execute(request) { read(SempodsStreamedResponse(it.code, it.body.byteStream(), headerLookup(it.headers))) }

  /**
   * The one shape a refused request takes. The server's own body travels with it: a pod answers
   * scope refusals and precondition failures with a reason, and swallowing it turns every
   * diagnosis into a server-log expedition.
   *
   * It travels twice — inside the message, which is written for a log line and therefore says
   * which call failed, and on its own in [SempodsClientException.responseBody], for a caller that
   * forwards the reason onward and must not forward this process's URLs with it.
   */
  fun failure(method: String, url: URI, statusCode: Int, body: String?): SempodsClientException =
    SempodsClientException(
      "$method $url failed: HTTP $statusCode — $body",
      statusCode = statusCode,
      responseBody = body,
    )

  /**
   * Reads a field the route's contract guarantees. A missing one is a broken contract rather than
   * an empty answer, so it fails here instead of becoming a null further up.
   */
  fun requiredText(root: JsonNode, field: String, url: URI, body: String): String =
    root.path(field).takeIf { it.isTextual }?.asText()
      ?: throw SempodsClientException("Response from $url missing '$field': ${body.take(200)}")

  /** Path-segment encoding: `URLEncoder` plus the `+`→`%20` correction it does not make. */
  fun urlPathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  /** A base URL with a guaranteed trailing slash, so [URI.resolve] appends instead of replacing. */
  fun baseWithTrailingSlash(baseUrl: URI): URI =
    URI(baseUrl.toString().trimEnd('/') + "/")

  private fun <T> execute(request: SempodsRequest, read: (Response) -> T): T {
    guardTarget(request.uri)
    val call = clientFor(request.callTimeout).newCall(toOkHttpRequest(request))
    val slot = SempodsCallSlot.current()
    slot?.bind(call::cancel)
    return try {
      call.execute().use(read)
    } catch (e: SsrfBlockedException) {
      throw notPubliclyAddressable(request.uri, e.message ?: "blocked address", e)
    } finally {
      slot?.unbind()
    }
  }

  /**
   * The address check the DNS hook structurally cannot do: an IP-literal host is never resolved, so
   * nothing asks `VettingDns` about `http://169.254.169.254/`. Runs before the call, so a refused
   * target produces no request at all rather than one that is merely not read.
   */
  private fun guardTarget(uri: URI) {
    val refusal = guard?.refuseTarget(uri) ?: return
    throw when (refusal.kind) {
      SempodsUrlPolicy.Refusal.Kind.SCHEME -> SempodsClientException("Not an HTTP(S) URI: '$uri'")
      SempodsUrlPolicy.Refusal.Kind.NO_HOST -> SempodsClientException("URI has no host: '$uri'")
      SempodsUrlPolicy.Refusal.Kind.NON_GLOBAL_HOST ->
        notPubliclyAddressable(uri, refusal.detail, SsrfBlockedException(refusal.detail))
    }
  }

  /**
   * One wording for both address layers, and the cause travels with it. The cause is what a caller
   * classifies on: a blocked address means "this pod URL is wrong", never "this token is dead", and
   * an exception that dropped its cause cannot tell the two apart.
   */
  private fun notPubliclyAddressable(uri: URI, reason: String, cause: Throwable) =
    SempodsClientException("Host '${uri.host}' of '$uri' is not publicly addressable — $reason", cause = cause)

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

  private fun toOkHttpRequest(request: SempodsRequest): Request {
    val builder = Request.Builder().url(request.uri.toString())
    // `addHeader`, matching what the previous JDK builder's `header` did: each name is set once by
    // the callers here, and adding keeps a caller free to send a repeated header if one ever needs to.
    request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
    return builder.method(request.method, requestBody(request.method, request.body)).build()
  }

  /**
   * `null` for the methods that must not carry a body, an empty body otherwise — so a DELETE still
   * goes out with `Content-Length: 0` the way it did before.
   */
  private fun requestBody(method: String, body: SempodsBody?): RequestBody? = when {
    body != null -> toRequestBody(body)
    method == "GET" || method == "HEAD" -> null
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
    is SempodsBody.Stream -> SuppliedStreamBody(body.size, body.open)
  }

  private fun headerLookup(headers: Headers): (String) -> String? = { headers[it] }

  /**
   * A body the transport may write more than once: OkHttp retransmits a request when a pooled
   * connection turns out to be stale, and [SempodsBody.Stream] promises a fresh stream per attempt.
   * Saying so through `isOneShot() = false` is what makes that retransmit legal rather than a silent
   * upload of zero bytes.
   *
   * A known [size] means a definite `Content-Length`; `-1` means chunked.
   */
  private class SuppliedStreamBody(
    private val size: Long?,
    private val open: () -> InputStream,
  ) : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = size ?: -1L
    override fun isOneShot(): Boolean = false
    override fun writeTo(sink: BufferedSink) {
      open().source().use { sink.writeAll(it) }
    }
  }

  companion object {

    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

    /**
     * The one client the whole process shares — see the note on the connection pool above. Timeouts
     * are not set here: a transport derives its own through `newBuilder()`, which keeps this pool
     * and this dispatcher. Private, so the engine type does not reach a consumer through a factory
     * either.
     */
    private val SHARED: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    }
  }
}
