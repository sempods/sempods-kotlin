package org.sempods.commons.trace

import java.util.concurrent.ThreadLocalRandom

/**
 * One hop of a W3C Trace Context, as defined by https://www.w3.org/TR/trace-context/: the
 * `traceparent` header and the `tracestate` that travels with it.
 *
 * The `traceparent` wire format is four dash-separated fields:
 *
 * ```
 * 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-03
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
 *
 * @property sampled the flags byte's `sampled` bit (`0x01`).
 * @property randomTraceId the flags byte's `random-trace-id` bit (`0x02`, Trace Context Level 2):
 *   at least the right-most seven bytes of [traceId] are random.
 * @property traceState the `tracestate` received with this trace, as [parse] normalised it, or
 *   `null` when none arrived or it was discarded. sempods adds no entry of its own, so every hop of
 *   the trace sends this value as it is.
 */
data class TraceContext(
  val traceId: String,
  val spanId: String,
  val sampled: Boolean = true,
  val randomTraceId: Boolean = false,
  val traceState: String? = null,
) {

  /** The `traceparent` header value for this hop. The reserved flag bits are always `0`. */
  fun toHeader(): String {
    val flags = (if (sampled) FLAG_SAMPLED else 0) or (if (randomTraceId) FLAG_RANDOM_TRACE_ID else 0)
    return "$VERSION-$traceId-$spanId-${"%02x".format(flags)}"
  }

  /**
   * The context an outgoing call should carry: same journey, new hop. Callers must send this
   * rather than their own [toHeader], otherwise every process on the path claims the same span.
   */
  fun newChild(): TraceContext = copy(spanId = randomSpanId())

  companion object {

    const val TRACEPARENT = "traceparent"
    const val TRACESTATE = "tracestate"

    private const val VERSION = "00"
    private const val INVALID_VERSION = "ff"
    private const val FLAG_SAMPLED = 0x01
    private const val FLAG_RANDOM_TRACE_ID = 0x02

    private const val TRACE_ID_LENGTH = 32
    private const val SPAN_ID_LENGTH = 16

    private const val MAX_TRACESTATE_ENTRIES = 32
    private const val MAX_TRACESTATE_LENGTH = 512
    private const val MAX_TRACESTATE_ENTRY_LENGTH = 128

    private val ALL_ZERO_TRACE_ID = "0".repeat(TRACE_ID_LENGTH)
    private val ALL_ZERO_SPAN_ID = "0".repeat(SPAN_ID_LENGTH)

    // A list-member, §3.3.1.3: simple-key or multi-tenant-key, "=", value.
    private val TRACESTATE_ENTRY = Regex(
      "(?:[a-z][a-z0-9_*/-]{0,255}|[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13})" +
        "=[\\x20-\\x2b\\x2d-\\x3c\\x3e-\\x7e]{0,255}[\\x21-\\x2b\\x2d-\\x3c\\x3e-\\x7e]",
    )

    /**
     * Parses an incoming `traceparent` and the `tracestate` that came with it, or returns `null`
     * if the `traceparent` is absent or malformed.
     *
     * The spec is explicit that a malformed header is to be treated as absent — the receiver
     * starts a new trace rather than trying to repair what it was given. Callers therefore do
     * `parse(traceparent, tracestate) ?: random()`, and the fresh trace carries no `tracestate`.
     *
     * Unknown future versions are parsed leniently (only the first four fields are read), which
     * is what keeps the chain intact if something ahead of us upgrades first. Version `ff` is
     * invalid by definition. Of the flags byte only [sampled] and [randomTraceId] are kept, so
     * the reserved bits leave as `0`.
     *
     * [tracestate] is the combined header value, several header lines joined with `,`. It is read
     * only when `traceparent` is valid, and it never makes a valid `traceparent` fail:
     *
     * | `tracestate` | [traceState] |
     * |---|---|
     * | Absent, blank or only empty entries | `null` |
     * | Valid entries | The entries without surrounding white space and empty entries, joined with `,` |
     * | An invalid entry, a key twice, or more than 32 entries | `null` |
     * | Longer than 512 characters | Whole entries removed until it fits: those over 128 characters first, then from the end |
     */
    fun parse(traceparent: String?, tracestate: String? = null): TraceContext? {
      val fields = traceparent?.trim()?.takeIf { it.isNotEmpty() }?.split('-') ?: return null
      if (fields.size < 4) return null

      val (version, traceId, spanId, flags) = fields
      if (!isLowerHex(version, 2) || version == INVALID_VERSION) return null
      // A version-00 header has exactly four fields; later versions may append more.
      if (version == VERSION && fields.size != 4) return null

      if (!isLowerHex(traceId, TRACE_ID_LENGTH) || traceId == ALL_ZERO_TRACE_ID) return null
      if (!isLowerHex(spanId, SPAN_ID_LENGTH) || spanId == ALL_ZERO_SPAN_ID) return null
      if (!isLowerHex(flags, 2)) return null

      val flagBits = flags.toInt(16)
      return TraceContext(
        traceId = traceId,
        spanId = spanId,
        sampled = (flagBits and FLAG_SAMPLED) != 0,
        randomTraceId = (flagBits and FLAG_RANDOM_TRACE_ID) != 0,
        traceState = normalizeTraceState(tracestate),
      )
    }

    /**
     * A fresh trace. Marked as sampled because nothing here samples — pretending otherwise
     * would tell a downstream collector to drop the very traces we generate. Marked
     * [randomTraceId] because every bit of the trace id is random.
     */
    fun random(): TraceContext =
      TraceContext(traceId = randomTraceId(), spanId = randomSpanId(), randomTraceId = true)

    private fun normalizeTraceState(header: String?): String? {
      val entries = header?.split(',')?.map { it.trim(' ', '\t') }?.filter { it.isNotEmpty() } ?: return null
      if (entries.size > MAX_TRACESTATE_ENTRIES || entries.any { !TRACESTATE_ENTRY.matches(it) }) return null
      if (entries.distinctBy { it.substringBefore('=') }.size != entries.size) return null

      val kept = entries.toMutableList()
      while (kept.sumOf { it.length } + kept.size - 1 > MAX_TRACESTATE_LENGTH) {
        val oversized = kept.indexOfLast { it.length > MAX_TRACESTATE_ENTRY_LENGTH }
        kept.removeAt(if (oversized >= 0) oversized else kept.lastIndex)
      }
      return kept.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

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
