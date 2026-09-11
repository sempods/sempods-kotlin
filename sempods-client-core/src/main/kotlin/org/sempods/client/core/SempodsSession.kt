package org.sempods.client.core

import org.sempods.commons.trace.TraceContext
import org.sempods.commons.trace.TraceContextHolder
import java.io.IOException
import java.net.URI
import java.time.Duration

/**
 * One pod, one credential, and the transport they run on.
 *
 * A session is cheap: it holds a validated [SempodsPodBase], a [SempodsRequestAuth] and a reference
 * to a [SempodsTransport] that other sessions may share. An application serving many pods builds
 * one per pod and owns its own lookup — where the pods come from is not a question a library about
 * a pod addressed by its own URL can answer.
 *
 * **This is the extension seam.** An endpoint group, a protocol module or a consumer's own route
 * builds a request with [newRequest] and runs it with [execute]. Nothing else is needed and nothing
 * private is reachable: authentication, target confinement, cancellation, deadlines, admission and
 * body lifetime all apply to an extension exactly as they apply to a built-in route.
 *
 * **A credential never leaves its pod.** [newRequest] resolves against this session's base, and
 * [execute] checks the target again — so a request assembled through one session and executed
 * through another is refused rather than sent with the wrong credential.
 *
 * ```java
 * var transport = SempodsTransport.builder().build();
 * var session = SempodsSession.builder(SempodsPodBase.of("https://pods.example/alice"))
 *     .transport(transport)
 *     .auth(SempodsRequestAuth.apiKeyHeader("X-Api-Key", key))
 *     .build();
 *
 * SempodsResponse<String> contexts = session.executeText(
 *     session.newRequest("GET", "_system/contexts").setHeader("Accept", "application/json").build());
 * ```
 */
class SempodsSession private constructor(
  val podBase: SempodsPodBase,
  private val transport: SempodsTransport,
  private val auth: SempodsRequestAuth,
  private val defaultTimeout: Duration,
) {

  /**
   * Starts a request against [podRelativePath] under this session's pod.
   *
   * [method] is any token, so HEAD, OPTIONS and a protocol extension's own verb need no change
   * here. The path is already percent-encoded — [SempodsUrlEncoding.pathSegment] encodes one
   * segment — and may carry a query after `?`.
   *
   * No credential is attached here. [SempodsRequestAuth] applies one per attempt, after assembly.
   */
  fun newRequest(method: String, podRelativePath: String): SempodsRequest.Builder {
    val builder = SempodsRequest.Builder(podBase.resolve(podRelativePath), method, null)
    TraceContextHolder.get()?.let { traceContext ->
      builder.setHeader(TraceContext.TRACEPARENT, traceContext.newChild().toHeader())
    }
    return builder
  }

  /**
   * Runs [request] and turns its body into a value through [handler].
   *
   * The handler sees every response, failure statuses included — see [SempodsBodyHandler] for why.
   * Its stream is closed when it returns, throws or exits early.
   *
   * Declares [IOException] because a handler may throw one and it travels out unchanged: a Java
   * caller has to be able to write `catch (IOException e)` around this, and without the
   * declaration `javac` rejects that clause as unreachable.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun <T> execute(
    request: SempodsRequest,
    handler: SempodsBodyHandler<T>,
    operation: SempodsOperation = SempodsOperation.current() ?: SempodsOperation(),
  ): SempodsResponse<T> = SempodsExecution(transport, auth, defaultTimeout).run(
    request = confine(request),
    handler = handler,
    operation = operation,
  )

  /** The whole body as UTF-8 text, buffered over the same scoped execution. */
  @JvmOverloads
  @Throws(IOException::class)
  fun executeText(
    request: SempodsRequest,
    operation: SempodsOperation = SempodsOperation.current() ?: SempodsOperation(),
  ): SempodsResponse<String> = execute(request, { it.bodyText() }, operation)

  /** The whole body as bytes, buffered over the same scoped execution. */
  @JvmOverloads
  @Throws(IOException::class)
  fun executeBytes(
    request: SempodsRequest,
    operation: SempodsOperation = SempodsOperation.current() ?: SempodsOperation(),
  ): SempodsResponse<ByteArray> = execute(request, { it.bodyBytes() }, operation)

  /**
   * The check that keeps a credential with its pod.
   *
   * A [SempodsRequest] is a plain object holding an absolute URI, so nothing about it remembers
   * which session built it. Without this, handing one to another session's [execute] would send
   * that session's credential to this pod — a same-host sibling path, a traversal or an outright
   * foreign target all arrive the same way.
   */
  private fun confine(request: SempodsRequest): SempodsRequest {
    if (request.uri in podBase) return request
    throw SempodsTransportException(
      "'${request.uri}' is not under this session's pod '$podBase'. A request built for one pod " +
        "cannot be executed by the session of another; build it with that session's newRequest.",
    )
  }

  class Builder internal constructor(private val podBase: SempodsPodBase) {
    private var transport: SempodsTransport? = null
    private var auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()
    private var timeout: Duration? = null

    /**
     * The transport to run on. Sessions that share one share its connection pool and its admission
     * budget; the caller owns its lifetime. Without this a transport is built for this session
     * alone, and closing it is then nobody's job but the caller's either.
     */
    fun transport(transport: SempodsTransport): Builder = apply { this.transport = transport }

    fun auth(auth: SempodsRequestAuth): Builder = apply { this.auth = auth }

    /** Overrides the transport's whole-operation deadline for every request of this session. */
    fun operationTimeout(timeout: Duration): Builder = apply { this.timeout = timeout }

    fun build(): SempodsSession {
      val transport = this.transport ?: SempodsTransport.builder().build()
      return SempodsSession(podBase, transport, auth, timeout ?: transport.defaultOperationTimeout)
    }
  }

  companion object {

    @JvmStatic
    fun builder(podBase: SempodsPodBase): Builder = Builder(podBase)

    @JvmStatic
    fun builder(podBaseUrl: URI): Builder = Builder(SempodsPodBase.of(podBaseUrl))
  }
}
