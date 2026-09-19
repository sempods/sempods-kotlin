package org.sempods.client.core

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Whether the pod exists, and when it was last written to: `{pod}/_system/meta/date-modified`, a
 * route of the reference server that the specification does not define.
 *
 * Every method sends the same request — `GET`, `Accept: application/json`, no query, no body —
 * authenticated as the session is. An anonymous session is enough: the timestamp is not
 * context-scoped, and the server serves it to anyone.
 *
 * The route answers `200` with `{"dateModified": "<instant>"}`, `null` for a pod never written to,
 * and `404` for a pod the server does not know. Both are answers ([SempodsResponse]); any other
 * status is a [SempodsStatusException].
 */
class SempodsPodMetadata private constructor(
  private val session: SempodsSession,
  private val exchange: Exchange,
) {

  /** `true` for a 200, `false` for a 404, decided by the status alone: the body is closed unread. */
  @Throws(IOException::class)
  fun exists(): Boolean = exchange.status(request(), ANSWERS) != 404

  /**
   * The pod's `dateModified`. The response body is null for a 404, and
   * [SempodsPodDateModified.dateModified] is null for a pod never written to.
   *
   * Unknown members are ignored. A `dateModified` that is neither `null` nor a string holding an
   * ISO-8601 instant, or a body that is not one JSON object, is a [SempodsDecodingException].
   */
  @Throws(IOException::class)
  fun dateModified(): SempodsResponse<SempodsPodDateModified> = exchange.run(request(), ANSWERS, DATE_MODIFIED)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun dateModifiedJson(): SempodsResponse<String> = exchange.run(request(), ANSWERS, BodyReading.TEXT)

  /** The same answer with the body as the bytes the server sent. */
  @Throws(IOException::class)
  fun dateModifiedBytes(): SempodsResponse<ByteArray> = exchange.run(request(), ANSWERS, BodyReading.BYTES)

  private fun request() = session.newRequest("GET", ROUTE).header("Accept", "application/json").build()

  internal companion object {

    @JvmSynthetic
    internal fun of(session: SempodsSession, exchange: Exchange): SempodsPodMetadata =
      SempodsPodMetadata(session, exchange)

    private const val ROUTE = "_system/meta/date-modified"

    private val ANSWERS = setOf(200, 404)

    private val DATE_MODIFIED = BodyReading<SempodsPodDateModified> { bytes, _ ->
      SempodsPodDateModified.of(decodeObject(bytes).stringOrNull("dateModified")?.let(::instant))
    }

    private fun instant(text: String): Instant =
      try {
        Instant.parse(text)
      } catch (_: DateTimeParseException) {
        throw ProtocolViolation("/dateModified: expected an ISO-8601 instant")
      }
  }
}
