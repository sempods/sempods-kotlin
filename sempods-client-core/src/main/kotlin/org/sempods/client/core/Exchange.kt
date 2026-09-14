package org.sempods.client.core

import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** The most an endpoint operation reads into memory. */
internal const val MAX_BODY_BYTES: Long = 16L * 1024 * 1024

/** How much of a refused answer's body a [SempodsStatusException] keeps. */
internal const val ERROR_EXCERPT_BYTES: Long = 4L * 1024

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
 * The response is closed before a body is decoded, so its admission slot is free while the decoding
 * runs. A failure of the network, of the deadline or of the core's own policy passes through as it is.
 */
internal class Exchange(
  private val calls: Call.Factory,
  private val maxBodyBytes: Long = MAX_BODY_BYTES,
) {

  /** The status of a 2xx or of a listed answer, with the body closed unread. */
  fun status(request: Request, answers: Set<Int>): Int = execute(request, answers, readBody = false).status

  fun <T : Any> run(request: Request, answers: Set<Int>, reading: BodyReading<T>): SempodsResponse<T> {
    val answer = execute(request, answers, readBody = true)
    val bytes = answer.bytes ?: return SempodsResponse(answer.status, answer.headers, body = null)
    val body = try {
      reading.read(bytes, answer.contentType)
    } catch (violation: ProtocolViolation) {
      throw SempodsDecodingException(
        "${answer.described} answered ${answer.status} with a body this operation cannot read: ${violation.detail}.",
        answer.status,
        answer.headers,
      )
    }
    return SempodsResponse(answer.status, answer.headers, body)
  }

  private fun execute(request: Request, answers: Set<Int>, readBody: Boolean): Answer =
    calls.newCall(request).execute().use { response ->
      // The request the pod received, whose URL names the pod's host; no query, which is where a
      // caller's parameters would be.
      val sent = response.request
      val described = "${sent.method} ${sent.url.newBuilder().query(null).fragment(null).build()}"
      when {
        response.isSuccessful -> Answer(described, response, if (readBody) bounded(response, described) else null)
        response.code in answers -> Answer(described, response, bytes = null)
        else -> throw SempodsStatusException(
          "$described answered ${response.code}, which this operation does not accept.",
          response.code,
          response.headers,
          excerpt(response),
        )
      }
    }

  private fun bounded(response: Response, described: String): ByteArray {
    val source = response.body.source()
    if (response.body.contentLength() > maxBodyBytes || source.request(maxBodyBytes + 1)) {
      throw SempodsDecodingException(
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

  private class Answer(val described: String, response: Response, val bytes: ByteArray?) {
    val status: Int = response.code
    val headers: Headers = response.headers
    val contentType: MediaType? = response.body.contentType()
  }
}
