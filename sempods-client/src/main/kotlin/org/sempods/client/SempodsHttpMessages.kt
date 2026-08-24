package org.sempods.client

import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The request/response vocabulary both clients speak, deliberately naming no HTTP engine.
 *
 * **Why these exist rather than the engine's own types.** [SempodsHttpTransport] is public only
 * because Kotlin's `internal` stops at the module boundary — it makes no API promise. Handing out
 * an engine's `Request`/`Response` would make one anyway: a consumer of a Maven Central artifact
 * would compile against that engine's major version, and the "no third-party dependencies" rule
 * (`docs/pod-client.md` §"The transport") would be a build-file detail rather than a
 * property of the API. Everything engine-specific therefore stops at the transport.
 *
 * The second reason is narrower and just as real: an engine response is usually `Closeable`, and a
 * type whose ~35 call sites each owe a `close()` is a connection leak waiting for the one site that
 * forgets. [SempodsResponse] carries an already-read body, and the streaming case is scoped
 * ([SempodsHttpTransport.sendStreaming]) so the response cannot outlive the block that reads it.
 */
sealed class SempodsBody {

  internal class Bytes(val value: ByteArray) : SempodsBody()

  /**
   * A body supplied on demand. **[open] is invoked once per attempt** and must yield a fresh stream
   * positioned at the first byte every time — a retry (the 401 retry in `SempodsPodClient`, or a
   * transport-level retransmit) replays it.
   *
   * [size] decides the framing only: with it the request carries a definite `Content-Length`,
   * without it a chunked body.
   */
  internal class Stream(val size: Long?, val open: () -> InputStream) : SempodsBody()

  internal object Empty : SempodsBody()

  companion object {
    fun bytes(value: ByteArray): SempodsBody = Bytes(value)

    /**
     * UTF-8 bytes of [value], and **only** the bytes: no charset is attached to the body, so the
     * `Content-Type` a caller sets with [SempodsRequest.Builder.header] is what travels. Engines
     * that would otherwise append `; charset=utf-8` to a string body cannot do it here.
     */
    fun text(value: String): SempodsBody = Bytes(value.toByteArray(StandardCharsets.UTF_8))

    fun stream(size: Long?, open: () -> InputStream): SempodsBody = Stream(size, open)

    fun empty(): SempodsBody = Empty
  }
}

/** A request to send. Built through [SempodsHttpTransport.newRequest], which binds the shared parts. */
class SempodsRequest internal constructor(
  val uri: URI,
  internal val method: String,
  internal val headers: List<Pair<String, String>>,
  internal val body: SempodsBody?,
  internal val callTimeout: Duration?,
) {

  class Builder internal constructor(
    private val uri: URI,
    private var callTimeout: Duration?,
  ) {
    private val headers = mutableListOf<Pair<String, String>>()
    private var method: String = "GET"
    private var body: SempodsBody? = null

    fun header(name: String, value: String): Builder = apply { headers += name to value }

    fun GET(): Builder = apply { method = "GET"; body = null }

    fun DELETE(): Builder = apply { method = "DELETE"; body = null }

    fun PUT(body: SempodsBody): Builder = apply { method = "PUT"; this.body = body }

    fun POST(body: SempodsBody): Builder = apply { method = "POST"; this.body = body }

    fun PATCH(body: SempodsBody): Builder = apply { method = "PATCH"; this.body = body }

    /**
     * Overrides the transport's whole-call deadline for this one request.
     * [Duration.ZERO] means **no** deadline — what a streaming read of an unbounded body needs,
     * since a whole-call timeout would cut it off mid-stream.
     */
    fun callTimeout(timeout: Duration): Builder = apply { this.callTimeout = timeout }

    fun build(): SempodsRequest = SempodsRequest(uri, method, headers.toList(), body, callTimeout)
  }
}

/** A response whose body is already read; nothing is left open for the caller to close. */
class SempodsResponse<T> internal constructor(
  val statusCode: Int,
  val body: T,
  private val headerLookup: (String) -> String?,
) {
  /** First value of [name], or `null`. Header names are matched case-insensitively. */
  fun header(name: String): String? = headerLookup(name)
}

/**
 * A response whose body is still on the wire. Valid **only** inside the
 * [SempodsHttpTransport.sendStreaming] block that produced it; the transport closes it afterwards.
 */
class SempodsStreamedResponse internal constructor(
  val statusCode: Int,
  private val stream: InputStream,
  private val headerLookup: (String) -> String?,
) {
  fun header(name: String): String? = headerLookup(name)

  fun bodyStream(): InputStream = stream

  /**
   * The body as text, truncated at [max] bytes — for the failure path, where the body is a server's
   * reason and not a payload. Reading an error body unbounded is how a 5xx from a large route turns
   * into an allocation the caller never asked for.
   */
  fun bodyTextCapped(max: Int = 4096): String =
    String(stream.readNBytes(max), StandardCharsets.UTF_8)
}
