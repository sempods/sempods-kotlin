package org.sempods.commons.trace

import java.util.concurrent.ThreadLocalRandom

/**
 * One hop of a W3C Trace Context (`traceparent`), as defined by
 * https://www.w3.org/TR/trace-context/.
 *
 * The wire format is four dash-separated fields:
 *
 * ```
 * 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
 * ^^ ^------------ traceId ---------^ ^--- spanId ---^ ^^ flags
 * version
 * ```
 *
 * [traceId] identifies the journey as a whole and never changes while a request travels; it is
 * the value to correlate log lines on. [spanId] identifies *this* hop — every caller mints a
 * fresh one via [newChild] before it sends, which is what turns a flat list of log lines into a
 * call tree.
 *
 * Instances are immutable; [TraceContextHolder] owns the per-thread binding.
 */
data class TraceContext(
  val traceId: String,
  val spanId: String,
  val sampled: Boolean = true,
) {

  /** The `traceparent` header value for this hop. */
  fun toHeader(): String = "$VERSION-$traceId-$spanId-${if (sampled) FLAG_SAMPLED else FLAG_NOT_SAMPLED}"

  /**
   * The context an outgoing call should carry: same journey, new hop. Callers must send this
   * rather than their own [toHeader], otherwise every process on the path claims the same span.
   */
  fun newChild(): TraceContext = copy(spanId = randomSpanId())

  companion object {

    const val TRACEPARENT = "traceparent"

    private const val VERSION = "00"
    private const val INVALID_VERSION = "ff"
    private const val FLAG_SAMPLED = "01"
    private const val FLAG_NOT_SAMPLED = "00"

    private const val TRACE_ID_LENGTH = 32
    private const val SPAN_ID_LENGTH = 16

    private val ALL_ZERO_TRACE_ID = "0".repeat(TRACE_ID_LENGTH)
    private val ALL_ZERO_SPAN_ID = "0".repeat(SPAN_ID_LENGTH)

    /**
     * Parses an incoming `traceparent`, or returns `null` if it is absent or malformed.
     *
     * The spec is explicit that a malformed header is to be treated as absent — the receiver
     * starts a new trace rather than trying to repair what it was given. Callers therefore do
     * `parse(header) ?: random()`.
     *
     * Unknown future versions are parsed leniently (only the first four fields are read), which
     * is what keeps the chain intact if something ahead of us upgrades first. Version `ff` is
     * invalid by definition.
     */
    fun parse(header: String?): TraceContext? {
      val fields = header?.trim()?.takeIf { it.isNotEmpty() }?.split('-') ?: return null
      if (fields.size < 4) return null

      val (version, traceId, spanId, flags) = fields
      if (!isLowerHex(version, 2) || version == INVALID_VERSION) return null
      // A version-00 header has exactly four fields; later versions may append more.
      if (version == VERSION && fields.size != 4) return null

      if (!isLowerHex(traceId, TRACE_ID_LENGTH) || traceId == ALL_ZERO_TRACE_ID) return null
      if (!isLowerHex(spanId, SPAN_ID_LENGTH) || spanId == ALL_ZERO_SPAN_ID) return null
      if (!isLowerHex(flags, 2)) return null

      // Only the "sampled" bit is defined; the rest of the flags byte is reserved.
      val sampled = (flags.toInt(16) and 0x01) != 0
      return TraceContext(traceId = traceId, spanId = spanId, sampled = sampled)
    }

    /**
     * A fresh trace. Marked as sampled because nothing here samples — pretending otherwise
     * would tell a downstream collector to drop the very traces we generate.
     */
    fun random(): TraceContext = TraceContext(traceId = randomTraceId(), spanId = randomSpanId())

    private fun randomTraceId(): String {
      // Trace ids identify, they do not authenticate: a non-cryptographic RNG is what the
      // OpenTelemetry SDKs use as well, and it avoids contending on SecureRandom per request.
      var high = ThreadLocalRandom.current().nextLong()
      var low = ThreadLocalRandom.current().nextLong()
      while (high == 0L && low == 0L) {
        high = ThreadLocalRandom.current().nextLong()
        low = ThreadLocalRandom.current().nextLong()
      }
      return "%016x%016x".format(high, low)
    }

    private fun randomSpanId(): String {
      var id = ThreadLocalRandom.current().nextLong()
      while (id == 0L) {
        id = ThreadLocalRandom.current().nextLong()
      }
      return "%016x".format(id)
    }

    private fun isLowerHex(value: String, length: Int): Boolean {
      if (value.length != length) return false
      return value.all { it in '0'..'9' || it in 'a'..'f' }
    }
  }
}
