package org.sempods.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.commons.okhttp.TraceparentInterceptor
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The transport the existing clients were written against, now a translation onto an OkHttp client
 * [SempodsOkHttp] configured.
 *
 * **This is the legacy surface, and it is where the JSON helpers stayed.** [objectMapper] and
 * [requiredText] name Jackson types, which is why the core does not have them: a consumer that
 * wants HTTP against a pod should not resolve an object mapper to get it. They remain here because
 * `PodWireClient` and `SempodsControlPlaneClient` use them, and moving those onto the core is
 * [#152](https://github.com/sempods/sempods-kotlin/issues/152).
 *
 * **It translates [SempodsRequest] and [SempodsResponse] onto OkHttp's.** A caller of this surface
 * never owes a `close()`: every call reads the body and closes the response before returning. Below
 * it are the core's guard and redirect policy, on one connection pool every transport shares.
 *
 * **A token per request rather than a credential per session.** That is this surface's model. A
 * consumer wanting a credential that can be replaced, decorated or refreshed builds a
 * [org.sempods.client.core.SempodsSession] instead.
 */
class SempodsHttpTransport @JvmOverloads constructor(
  timeouts: SempodsHttpTimeouts = SempodsHttpTimeouts(),
  guard: SempodsOutboundGuard? = null,
) {

  /**
   * The core's guard, redirect policy and deadlines, with OkHttp's own resend left on and no admission
   * budget.
   *
   * **OkHttp's resend, for the requests this surface builds**: they carry no session, so the core's
   * resend rule — which is a session's — does not reach them, and their callers were written against
   * a transport that bridged a pooled connection the server had already closed. A repeat of one sends
   * nothing stale, because the bearer is fixed on the request before it arrives. A session's request
   * sent through [calls] gets the core's rule instead, and OkHttp's is off below the session.
   */
  private val httpClient: OkHttpClient = SempodsOkHttp.install(
    SHARED.newBuilder()
      .connectTimeout(timeouts.connect)
      .readTimeout(timeouts.read)
      .writeTimeout(timeouts.write),
    guard,
    admission = null,
  )
    // The caller's trace, for every request this client sends rather than only the ones [newRequest]
    // builds: [calls] hands it to endpoint groups that build their own, and a header set while
    // building reaches none of those. It leaves a request that already carries the header alone, so
    // [newRequest]'s own remains what goes out.
    //
    // **Ahead of the session interceptor**, which `install` puts at index 0 and which runs the
    // attempts below itself. Behind it the header would be stamped afresh per attempt, and one
    // logical call would reach the pod as a different span each time it is resent.
    .apply { interceptors().add(0, TraceparentInterceptor) }
    // After `install`, which would otherwise read an unset deadline and put its own in: the one this
    // surface's callers configured wins, `Duration.ZERO` included.
    .callTimeout(timeouts.call)
    .build()

  /**
   * Variants for requests that override the whole-call deadline. Cached because a per-request
   * `newBuilder()` would allocate on every call; each variant still shares the pool and dispatcher.
   */
  private val byCallTimeout = ConcurrentHashMap<Duration, OkHttpClient>()

  /**
   * The factory a core endpoint group runs its calls on, sharing this transport's connection pool,
   * guard and redirect policy instead of opening a second of each.
   *
   * **No caller in this module uses it today.** It is what [SempodsControlPlaneClient] and
   * `PodWireClient` reach for when they move onto the core ([#152](https://github.com/sempods/sempods-kotlin/issues/152)),
   * and the slot binding below is the part that would be silently missing if it were rebuilt then.
   *
   * A session's request needs the policy [SempodsOkHttp] installs here to resolve at all, which is
   * what makes this a factory over that client rather than a bare one.
   *
   * **It binds [SempodsCallSlot] the way [execute] does.** The slot is this surface's cancel handle
   * and it is per thread, so a call a group makes inside `SempodsCallSlot.using` has to reach it
   * too — otherwise cancelling marks the slot and leaves the socket blocked until a timeout. The
   * core's own handle is `Call.cancel()`, which is what the slot ends up calling.
   */
  internal val calls: Call.Factory = Call.Factory { request -> SlotBoundCall(httpClient.newCall(request)) }

  /** Shared by the clients above so a response is parsed the same way wherever it is read. */
  val objectMapper: ObjectMapper = ObjectMapper()

  /**
   * Starts a request: the bearer when there is one, and the caller's W3C trace when one is bound.
   * Each request gets its own span id; the trace id is what carries.
   *
   * **[token] is nullable on purpose.** A pod serves its public contexts to an unauthenticated
   * caller, and reading public data is the first thing anyone does with a pod they do not own.
   * Omitting the header is therefore a supported mode; what a caller may do without a token is the
   * server's decision, expressed as a 401 or a filtered answer.
   */
  // TODO: an explicit `traceparent` from a caller is *appended* here, not substituted — the builder
  //   keeps a list and `toOkHttpRequest` calls `addHeader` per entry, so a caller setting the header
  //   after `newRequest` puts two on the wire. The interceptor paths and `SempodsSession.newRequest`
  //   leave the caller's header standing, which is what `docs/request-tracing.md` describes; this
  //   path should match it. Needs replacement semantics on `SempodsRequest.Builder.header` and a
  //   test. No caller in the tree does it today.
  @JvmOverloads
  fun newRequest(uri: URI, token: String? = null): SempodsRequest.Builder {
    val builder = SempodsRequest.Builder(uri, null)
    token?.let { builder.header("Authorization", "Bearer $it") }
    TraceContextHolder.get()?.let { traceContext ->
      builder.header(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
    }
    return builder
  }

  fun send(request: SempodsRequest): SempodsResponse<String> =
    execute(request) { response -> SempodsResponse(response.code, response.body.string(), response.headers::get) }

  fun sendBytes(request: SempodsRequest): SempodsResponse<ByteArray> =
    execute(request) { response -> SempodsResponse(response.code, response.body.bytes(), response.headers::get) }

  /**
   * Streams a response body through [read] and closes it afterwards, whatever [read] does.
   *
   * Scoped rather than returned so the body cannot outlive the call that owns it: an unclosed
   * response strands a pooled connection, and a signature that hands one out makes every call site
   * responsible for remembering. The core does hand one out — its callers are written for that.
   */
  fun <T> sendStreaming(request: SempodsRequest, read: (SempodsStreamedResponse) -> T): T =
    execute(request) { response ->
      read(SempodsStreamedResponse(response.code, response.body.byteStream(), response.headers::get))
    }

  /**
   * The one shape a refused request takes on this surface. The server's own body travels with it:
   * a pod answers scope refusals and precondition failures with a reason, and swallowing it turns
   * every diagnosis into a server-log expedition.
   *
   * It travels twice — inside the message, written for a log line, and on its own in
   * [SempodsClientException.responseBody], for a caller that forwards the reason onward and must
   * not forward this process's URLs with it.
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
  fun baseWithTrailingSlash(baseUrl: URI): URI = URI(baseUrl.toString().trimEnd('/') + "/")

  /**
   * Runs one request, hands the response to [read] and closes it before returning.
   *
   * The core's cancellation is `Call.cancel()`; [SempodsCallSlot] binds onto it here, which is the
   * bridge `PodIo` still needs. No authentication runs: this surface puts its bearer on the request
   * before it arrives, so the core's session auth is not involved.
   *
   * **Sending is translated, reading is not.** The core made every refusal of its own one type, an
   * [java.io.IOException] beside the engine's. The callers above were written against several, and
   * two of them decide real behaviour on the distinction: `PodFailures.isRetryablePodFailure` walks
   * for a rate-limit or SSRF cause, and `McpEndpoint.runTool` catches [SempodsClientException] to
   * keep this process's URLs out of a message that goes to a language model. What [read] throws —
   * this surface's own exception with its status code included — reaches the caller unchanged.
   * Migrating the callers onto the core's shape is
   * [#152](https://github.com/sempods/sempods-kotlin/issues/152).
   */
  private fun <T> execute(request: SempodsRequest, read: (okhttp3.Response) -> T): T {
    val call = clientFor(request.callTimeout).newCall(toOkHttpRequest(request))
    val slot = SempodsCallSlot.current()
    val owning = slot?.bind(call::cancel)
    try {
      val response = try {
        call.execute()
      } catch (e: org.sempods.client.core.SempodsClientException) {
        // A refusal this library made — a blocked address, a spent budget. The cause carries what a
        // consumer classifies on, which is why it travels rather than being flattened into the text.
        throw SempodsClientException(e.message.orEmpty(), cause = e.cause ?: e)
      } catch (e: org.sempods.client.core.net.SsrfBlockedException) {
        // Thrown from inside the connection path, where the resolver vets every address. It is an
        // `UnknownHostException` so the engine treats it as a resolution failure; this surface's
        // callers expect their own type, with the cause kept for the same classification.
        throw SempodsClientException(
          "Host of '${request.uri}' is not publicly addressable — ${e.message}",
          cause = e,
        )
      }
      return response.use(read)
    } finally {
      slot?.unbind(owning)
    }
  }

  private fun clientFor(callTimeout: Duration?): OkHttpClient =
    if (callTimeout == null) httpClient
    else byCallTimeout.computeIfAbsent(callTimeout) { httpClient.newBuilder().callTimeout(it).build() }

  private fun toOkHttpRequest(request: SempodsRequest): Request {
    // `HttpUrl` is http or https and nothing else, which is the one refusal this surface's callers
    // expect before a call exists.
    val url = request.uri.toString().toHttpUrlOrNull()
      ?: throw SempodsClientException("Not an HTTP(S) URI: '${request.uri}'")
    val builder = Request.Builder().url(url)
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
   * `RequestBody.contentType()` whenever that is non-null.
   */
  private fun toRequestBody(body: SempodsBody): RequestBody = when (body) {
    is SempodsBody.Empty -> EMPTY_BODY
    is SempodsBody.Bytes -> body.value.toRequestBody(null)
    is SempodsBody.Stream -> SuppliedStreamBody(body.size, body.open)
  }

  /**
   * A body the transport may write more than once: OkHttp retransmits a request when a pooled
   * connection turns out to be stale, and [SempodsBody.Stream] promises a fresh stream per attempt.
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

  /**
   * A call bound to the thread's [SempodsCallSlot] for as long as it is blocked in [execute].
   *
   * `enqueue` is passed through unbound: the slot belongs to the thread that blocks, and an
   * enqueued call has already left it. The core's asynchronous path blocks on a virtual thread of
   * its own, where a slot is bound only if its caller bound one — as with [execute] here.
   */
  private class SlotBoundCall(private val delegate: Call) : Call by delegate {

    override fun execute(): okhttp3.Response {
      val slot = SempodsCallSlot.current() ?: return delegate.execute()
      val owning = slot.bind(delegate::cancel)
      return try {
        delegate.execute()
      } finally {
        slot.unbind(owning)
      }
    }

    override fun clone(): Call = SlotBoundCall(delegate.clone())
  }

  private companion object {
    val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

    /** What every transport derives its client from, so all of them share one pool and dispatcher. */
    val SHARED: OkHttpClient by lazy { OkHttpClient() }
  }
}
