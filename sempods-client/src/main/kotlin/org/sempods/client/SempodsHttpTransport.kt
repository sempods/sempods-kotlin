package org.sempods.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.sempods.client.core.SempodsTransport
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The transport the existing clients were written against, now a translation onto
 * [SempodsTransport].
 *
 * **This is the legacy surface, and it is where the JSON helpers stayed.** [objectMapper] and
 * [requiredText] name Jackson types, which is why the core does not have them: a consumer that
 * wants HTTP against a pod should not resolve an object mapper to get it. They remain here because
 * `SempodsClient`, `SempodsPodClient`, `PodWireClient` and `SempodsControlPlaneClient` use them
 * today, and moving those onto the core is
 * [#150](https://github.com/sempods/sempods-kotlin/issues/150) and
 * [#152](https://github.com/sempods/sempods-kotlin/issues/152).
 *
 * **What it translates, and why that is now the only reason it exists.** The core speaks OkHttp's
 * `Request` and `Response`; this surface speaks [SempodsRequest] and [SempodsResponse], whose point
 * was that a caller never owes a `close()`. Every call here therefore reads the body and closes the
 * response before returning. Below it there is one connection pool, one outbound guard and one set
 * of deadlines — the core's.
 *
 * **A token per request rather than a credential per session.** That is this surface's model. A
 * consumer wanting a credential that can be replaced, decorated or refreshed builds a
 * [org.sempods.client.core.SempodsSession] instead.
 */
class SempodsHttpTransport @JvmOverloads constructor(
  timeouts: SempodsHttpTimeouts = SempodsHttpTimeouts(),
  guard: SempodsOutboundGuard? = null,
) {

  private val transport: SempodsTransport = SempodsTransport.builder()
    .guard(guard)
    .timeouts(connect = timeouts.connect, read = timeouts.read, write = timeouts.write, call = timeouts.call)
    .build()

  /**
   * Variants for requests that override the whole-call deadline. Cached because a per-request
   * `newBuilder()` would allocate on every call; each variant still shares the pool and dispatcher.
   */
  private val byCallTimeout = ConcurrentHashMap<Duration, OkHttpClient>()

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
    execute(request) { response -> SempodsResponse(response.code, response.body.string(), response::header) }

  fun sendBytes(request: SempodsRequest): SempodsResponse<ByteArray> =
    execute(request) { response -> SempodsResponse(response.code, response.body.bytes(), response::header) }

  /**
   * Streams a response body through [read] and closes it afterwards, whatever [read] does.
   *
   * Scoped rather than returned so the body cannot outlive the call that owns it: an unclosed
   * response strands a pooled connection, and a signature that hands one out makes every call site
   * responsible for remembering. The core does hand one out — its callers are written for that.
   */
  fun <T> sendStreaming(request: SempodsRequest, read: (SempodsStreamedResponse) -> T): T =
    execute(request) { response ->
      read(SempodsStreamedResponse(response.code, response.body.byteStream(), response::header))
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
   * Runs one request and closes the response before returning.
   *
   * The core's cancellation is `Call.cancel()`; [SempodsCallSlot] binds onto it here, which is the
   * bridge `PodIo` still needs. No authentication runs: this surface puts its bearer on the request
   * before it arrives, so the core's session auth is not involved.
   */
  private fun <T> extracted(request: SempodsRequest, read: (okhttp3.Response) -> T): T {
    val call = clientFor(request.callTimeout).newCall(toOkHttpRequest(request))
    val slot = SempodsCallSlot.current()
    slot?.bind(call::cancel)
    return try {
      call.execute().use(read)
    } finally {
      slot?.unbind()
    }
  }

  /**
   * Runs one request and hands this surface's callers the failure shape they classify on.
   *
   * The core made every refusal of its own one type, an [java.io.IOException] beside the engine's.
   * The callers above were written against several, and two of them decide real behaviour on the
   * distinction: `PodFailures.isRetryablePodFailure` walks for a rate-limit or SSRF cause, and
   * `McpEndpoint.runTool` catches [SempodsClientException] to keep this process's URLs out of a
   * message that goes to a language model. Migrating them onto the core's shape is
   * [#152](https://github.com/sempods/sempods-kotlin/issues/152); until then this translates.
   */
  private fun <T> execute(request: SempodsRequest, read: (okhttp3.Response) -> T): T =
    try {
      extracted(request, read)
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
    } catch (e: IllegalArgumentException) {
      // `Request.Builder.url` refuses a non-HTTP scheme before a call exists. The guard used to
      // answer that one, and its callers still expect this surface's exception for it.
      throw SempodsClientException("Not an HTTP(S) URI: '${request.uri}'", cause = e)
    }

  private fun clientFor(callTimeout: Duration?): OkHttpClient =
    if (callTimeout == null) transport.httpClient
    else byCallTimeout.computeIfAbsent(callTimeout) { transport.httpClient.newBuilder().callTimeout(it).build() }

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

  private companion object {
    val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
  }
}
