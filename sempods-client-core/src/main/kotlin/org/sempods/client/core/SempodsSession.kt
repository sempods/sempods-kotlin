package org.sempods.client.core

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.io.IOException

/**
 * One pod, one credential, and the transport they run on.
 *
 * A session is cheap: it holds a validated [SempodsPodBase], a [SempodsRequestAuth] and a reference
 * to a [SempodsTransport] that other sessions may share. An application serving many pods builds
 * one per pod and owns its own lookup — where the pods come from is not a question a library about
 * a pod addressed by its own URL can answer.
 *
 * **The request and the response are OkHttp's.** There is no wrapper to learn: build a
 * [Request.Builder] with [newRequest], run it with [execute], read the [Response] the way every
 * OkHttp response is read — and close it, which `try`-with-resources or Kotlin's `use` does.
 *
 * ```java
 * var session = SempodsSession.builder(SempodsPodBase.of("https://pods.example/alice"))
 *     .transport(transport)
 *     .auth(SempodsRequestAuth.apiKeyHeader("X-Api-Key", key))
 *     .build();
 *
 * var request = session.newRequest("GET", "_system/contexts")
 *     .header("Accept", "application/json")
 *     .build();
 * try (Response response = session.execute(request)) {
 *   String contexts = response.body().string();
 * }
 * ```
 *
 * **This is also the extension seam.** An endpoint group, a protocol module or a consumer's own
 * route gets authentication, target confinement, the outbound guard, the deadlines and admission by
 * building and running through here, and needs nothing private. Any method token works, so HEAD,
 * OPTIONS and an extension's own verb need no change.
 *
 * **A credential never leaves its pod.** [newRequest] resolves against this session's base, and
 * [execute] checks the target again — so a request assembled through one session and executed
 * through another is refused rather than sent with the wrong credential. The same check runs after
 * authentication, so a mechanism that rewrote the URL cannot carry the credential elsewhere.
 */
class SempodsSession private constructor(
  val podBase: SempodsPodBase,
  val transport: SempodsTransport,
  private val auth: SempodsRequestAuth,
) {

  /**
   * Starts a request against [podRelativePath] under this session's pod.
   *
   * [method] is any token, so HEAD, OPTIONS and a protocol extension's own verb need no change
   * here. The path is already percent-encoded; `HttpUrl.Builder.addPathSegment` encodes one
   * segment. A query may be attached after `?`.
   *
   * No credential is attached here. [SempodsRequestAuth] applies one per attempt, in [execute].
   */
  fun newRequest(method: String, podRelativePath: String): Request.Builder {
    val builder = Request.Builder()
      .url(podBase.resolve(podRelativePath))
      // An empty body for the verbs OkHttp requires one for, so a caller can name the method here
      // and attach the body afterwards — and so a DELETE still goes out with `Content-Length: 0`.
      .method(method, if (method in BODILESS_METHODS) null else EMPTY_BODY)
    TraceContextHolder.get()?.let { traceContext ->
      builder.header(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
    }
    return builder
  }

  /**
   * A call for [request], authenticated for its first attempt, with no admission slot taken.
   *
   * For a caller that wants the handle before the call runs — to cancel it from another thread, or
   * to enqueue it. **It does not retry**: authentication recovery is [execute]'s, because only a
   * caller that holds the slot can decide a second attempt is allowed. A cancelled call fails both
   * attempts either way, since [Call.cancel] reaches the connection.
   */
  @Throws(IOException::class)
  fun newCall(request: Request): Call =
    transport.httpClient.newCall(authenticated(confine(request), attempt = 1))

  /**
   * Runs [request] and hands back the open response.
   *
   * **The caller closes it.** That is OkHttp's contract and this does not soften it: the body may
   * be a stream the caller reads incrementally, and a wrapper that buffered it to avoid the `close`
   * would be the wrong trade for a context dump.
   *
   * Authentication is applied per attempt. On a refusal the session's [SempodsRequestAuth] is asked
   * whether another attempt would differ, and **only this method authorizes one** — at most once,
   * and only while the body can be sent again.
   */
  @Throws(IOException::class)
  fun execute(request: Request): Response {
    val confined = confine(request)
    transport.admit()
    var slotHeld = true
    try {
      val first = transport.httpClient.newCall(authenticated(confined, attempt = 1)).execute()
      if (!shouldRetry(first, confined)) {
        slotHeld = false
        return Admitted.wrap(first, transport)
      }
      // The refusal is closed here rather than handed on: its body was never read, and the response
      // the caller gets is the second attempt's.
      first.close()
      val second = transport.httpClient.newCall(authenticated(confined, attempt = 2)).execute()
      slotHeld = false
      return Admitted.wrap(second, transport)
    } finally {
      if (slotHeld) transport.release()
    }
  }

  private fun shouldRetry(response: Response, request: Request): Boolean {
    // A body that can be written once rules a second attempt out, whatever the mechanism says: the
    // alternative is a repeat that sends nothing and is answered 200.
    if (request.body?.isOneShot() == true) return false
    return auth.recover(response, attempt = 1)
  }

  private fun authenticated(request: Request, attempt: Int): Request {
    val builder = request.newBuilder()
    auth.apply(builder, attempt)
    val authenticated = builder.build()
    // Asked again after authentication: a mechanism is meant to set headers, and one that rewrote
    // the URL would carry this session's credential to another authority.
    if (authenticated.url != request.url) {
      throw SempodsClientException(
        "Authentication moved the request from '${request.url}' to '${authenticated.url}'. " +
          "A mechanism may set headers and nothing else.",
      )
    }
    return authenticated
  }

  /**
   * The check that keeps a credential with its pod.
   *
   * A `Request` is a plain object holding an absolute URL, so nothing about it remembers which
   * session built it. Without this, handing one to another session's [execute] would send that
   * session's credential to this pod — a same-host sibling path, a traversal or an outright foreign
   * target all arrive the same way.
   */
  private fun confine(request: Request): Request {
    if (request.url in podBase) return request
    throw SempodsClientException(
      "'${request.url}' is not under this session's pod '$podBase'. A request built for one pod " +
        "cannot be executed by the session of another; build it with that session's newRequest.",
    )
  }

  class Builder internal constructor(private val podBase: SempodsPodBase) {
    private var transport: SempodsTransport? = null
    private var auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()

    /**
     * The transport to run on. Sessions that share one share its connection pool and its admission
     * budget; the caller owns its lifetime. Without this a transport is built for this session
     * alone, and closing it is then the caller's job either way.
     */
    fun transport(transport: SempodsTransport): Builder = apply { this.transport = transport }

    fun auth(auth: SempodsRequestAuth): Builder = apply { this.auth = auth }

    fun build(): SempodsSession =
      SempodsSession(podBase, transport ?: SempodsTransport.builder().build(), auth)
  }

  companion object {

    private val BODILESS_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")

    private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    @JvmStatic
    fun builder(podBase: SempodsPodBase): Builder = Builder(podBase)

    @JvmStatic
    fun builder(podBaseUrl: HttpUrl): Builder = Builder(SempodsPodBase.of(podBaseUrl))

    @JvmStatic
    fun builder(podBaseUrl: String): Builder = Builder(SempodsPodBase.of(podBaseUrl))
  }
}

/**
 * Holds an admission slot until the response body is closed.
 *
 * The bytes are still arriving while a caller reads them and the connection is still held, so
 * releasing the slot when the status line arrived would let an unbounded number of half-read
 * responses exist under a limit that says otherwise. Wrapping the body is how a `close()` the
 * caller already owes becomes the release.
 */
private object Admitted {

  fun wrap(response: Response, transport: SempodsTransport): Response {
    val body = response.body
    return response.newBuilder()
      .body(ReleasingBody(body, transport))
      .build()
  }

  private class ReleasingBody(
    private val delegate: okhttp3.ResponseBody,
    private val transport: SempodsTransport,
  ) : okhttp3.ResponseBody() {

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun source() = delegate.source()

    override fun close() {
      try {
        delegate.close()
      } finally {
        if (released.compareAndSet(false, true)) transport.release()
      }
    }
  }
}
