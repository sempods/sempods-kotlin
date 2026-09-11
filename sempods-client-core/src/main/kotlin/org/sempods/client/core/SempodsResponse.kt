package org.sempods.client.core

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Turns a response still on the wire into a value.
 *
 * The handler runs **inside** the body's managed lifetime: the stream is open when it is called and
 * closed when it returns, throws or exits early. That is why the body is scoped rather than handed
 * back — a response object whose ~35 call sites each owe a `close()` is a connection leak waiting
 * for the one site that forgets.
 *
 * A handler sees **every** response, including a failure status. The transport does not turn a 404
 * or a 412 into an exception on its own: to an endpoint those are answers, and a core that threw
 * would force every caller to read them out of a `catch`. [SempodsResponse.requireSuccessful] is
 * there for callers that do want the throw.
 *
 * An interface rather than a Kotlin function type: a `(SempodsStreamedResponse) -> T` reaches Java
 * as `kotlin.jvm.functions.Function1` and cannot declare [IOException].
 */
fun interface SempodsBodyHandler<T> {
  @Throws(IOException::class)
  fun handle(response: SempodsStreamedResponse): T
}

/** A response whose body is already read; nothing is left open for the caller to close. */
class SempodsResponse<T> internal constructor(
  val statusCode: Int,
  val headers: SempodsHeaders,
  val body: T,
) {

  /** First value of [name], or `null`. Header names are matched case-insensitively. */
  fun header(name: String): String? = headers.first(name)

  val successful: Boolean get() = statusCode in 200..299

  /**
   * This response if its status is 2xx, otherwise [SempodsHttpException].
   *
   * Explicit because the transport refuses to guess. A 404 means "no such resource" on one route
   * and "this pod does not exist" on another; a 304 and a 412 are the expected outcomes of a
   * conditional request. Which of them is a failure is the caller's knowledge, so the throw is the
   * caller's call.
   */
  fun requireSuccessful(): SempodsResponse<T> {
    if (successful) return this
    throw SempodsHttpException(statusCode, headers, boundedDetail(body))
  }

  private fun boundedDetail(body: T): String? = when (body) {
    null -> null
    is String -> body.take(SempodsHttpException.MAX_ERROR_BODY_CHARS)
    is ByteArray -> String(body, StandardCharsets.UTF_8).take(SempodsHttpException.MAX_ERROR_BODY_CHARS)
    else -> null
  }
}

/**
 * A response whose body is still on the wire. Valid **only** inside the [SempodsBodyHandler] that
 * received it; the transport closes it afterwards.
 */
class SempodsStreamedResponse internal constructor(
  val statusCode: Int,
  val headers: SempodsHeaders,
  private val stream: InputStream,
) {

  fun header(name: String): String? = headers.first(name)

  val successful: Boolean get() = statusCode in 200..299

  fun bodyStream(): InputStream = stream

  /** The whole body as UTF-8 text. Unbounded: use it where the body is the payload. */
  fun bodyText(): String = String(stream.readAllBytes(), StandardCharsets.UTF_8)

  fun bodyBytes(): ByteArray = stream.readAllBytes()

  /**
   * The body as text, truncated at [max] bytes — for the failure path, where the body is a server's
   * reason and not a payload. Reading an error body unbounded is how a 5xx from a large route turns
   * into an allocation the caller never asked for.
   */
  @JvmOverloads
  fun bodyTextCapped(max: Int = SempodsHttpException.MAX_ERROR_BODY_CHARS): String =
    String(stream.readNBytes(max), StandardCharsets.UTF_8)
}
