package org.sempods.client

import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** The most an endpoint operation reads into memory. */
@field:JvmSynthetic
internal const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

/** How much of a refused answer's body a [SempodsStatusException] keeps. */
@field:JvmSynthetic
internal const val ERROR_EXCERPT_BYTES: Long = 4L * 1024

/** Every status OkHttp reads — any three digits — for an operation that takes each as an answer. */
@get:JvmSynthetic
internal val EVERY_STATUS: Set<Int> = (0..999).toSet()

/** How an operation reads a body that has already left the connection. */
internal fun interface BodyReading<T : Any> {

  /** The body as the operation's result, or a [ProtocolViolation] when it is not the route's document. */
  fun read(bytes: ByteArray, contentType: MediaType?): T

  companion object {

    val BYTES = BodyReading<ByteArray> { bytes, _ -> bytes }

    val TEXT = BodyReading<String> { bytes, contentType ->
      String(bytes, contentType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
    }
  }
}

/** A body that is not the route's document. [detail] says where and what was expected, and quotes nothing from it. */
internal class ProtocolViolation(val detail: String) : Exception(detail)

/**
 * The one way an endpoint operation runs. Raw text, raw bytes, a typed result and an existence check
 * that reads no body all go through [execute].
 *
 * `answers` are every status the operation accepts, 2xx included ([SempodsResponse]).
 *
 * The response is closed before a body is decoded, so its admission slot is free while the decoding
 * runs. [stream] is the exception, and says why. A failure of the network, of the deadline or of the
 * core's own policy passes through as it is.
 */
internal class Exchange(
  private val calls: Call.Factory,
  private val maxBodyBytes: Long = MAX_BODY_BYTES,
) {

  /** The status of a listed answer, with the body closed unread. */
  fun status(request: Request, answers: Set<Int>): Int = execute(request, answers, readBody = false).status

  /**
   * A listed answer whose body [reader] reads from the connection, inside the response's lifetime.
   *
   * Nothing is buffered and no limit applies: the reader sees the bytes as they arrive. A listed
   * answer outside 2xx does not reach it — that body is closed unread, as it is for [run].
   */
  fun <T : Any> stream(request: Request, answers: Set<Int>, reader: SempodsBodyReader<T>): SempodsResponse<T> =
    calls.newCall(request).execute().use { response ->
      refuseUnlisted(response, answers)
      if (!response.isSuccessful) {
        SempodsResponse.of(answered(response), response.code, response.headers, body = null, described(response))
      } else {
        SempodsResponse.of(
          answered(response), response.code, response.headers, reader.read(response.body.byteStream()), described(response),
        )
      }
    }

  fun <T : Any> run(request: Request, answers: Set<Int>, reading: BodyReading<T>): SempodsResponse<T> {
    val answer = execute(request, answers, readBody = true)
    val bytes = answer.bytes
      ?: return SempodsResponse.of(answer.url, answer.status, answer.headers, body = null, answer.described)
    val body = try {
      reading.read(bytes, answer.contentType)
    } catch (violation: ProtocolViolation) {
      throw SempodsDecodingException.of(
        "${answer.described} answered ${answer.status} with a body this operation cannot read: ${violation.detail}.",
        answer.status,
        answer.headers,
      )
    }
    return SempodsResponse.of(answer.url, answer.status, answer.headers, body, answer.described)
  }

  private fun execute(request: Request, answers: Set<Int>, readBody: Boolean): Answer =
    calls.newCall(request).execute().use { response ->
      refuseUnlisted(response, answers)
      val described = described(response)
      if (response.isSuccessful) {
        Answer(described, response, if (readBody) bounded(response, described) else null)
      } else {
        Answer(described, response, bytes = null)
      }
    }

  /** Throws unless [response] carries one of [answers], keeping what arrived of the refused body. */
  private fun refuseUnlisted(response: Response, answers: Set<Int>) {
    if (response.code in answers) return
    throw SempodsStatusException.of(
      "${described(response)} answered ${response.code}, which this operation does not accept.",
      response.code,
      response.headers,
      excerpt(response),
    )
  }

  /** The URL the response came from — after every follow-up OkHttp made — without a fragment. */
  private fun answered(response: Response): String = response.request.url.newBuilder().fragment(null).build().toString()

  /**
   * The method and URL as the request went out, with the real host rather than a session's placeholder,
   * and without the query, which is where a caller's parameters would be.
   */
  private fun described(response: Response): String {
    val sent = response.request
    return "${sent.method} ${sent.url.newBuilder().query(null).fragment(null).build()}"
  }

  private fun bounded(response: Response, described: String): ByteArray {
    val source = response.body.source()
    if (response.body.contentLength() > maxBodyBytes || source.request(maxBodyBytes + 1)) {
      throw SempodsDecodingException.of(
        "$described answered ${response.code} with a body over $maxBodyBytes bytes.",
        response.code,
        response.headers,
      )
    }
    return source.readByteArray()
  }

  /** What arrived of the body. One that breaks off while it is read still leaves the status to report. */
  private fun excerpt(response: Response): String =
    try {
      String(response.peekBody(ERROR_EXCERPT_BYTES).bytes(), Charsets.UTF_8)
    } catch (_: IOException) {
      ""
    }

  private inner class Answer(val described: String, response: Response, val bytes: ByteArray?) {
    val url: String = answered(response)
    val status: Int = response.code
    val headers: Headers = response.headers
    val contentType: MediaType? = response.body.contentType()
  }
}
