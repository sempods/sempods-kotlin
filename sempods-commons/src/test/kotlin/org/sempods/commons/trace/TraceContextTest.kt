package org.sempods.commons.trace

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceContextTest {

  private val validTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val validSpanId = "00f067aa0ba902b7"
  private val validHeader = "00-$validTraceId-$validSpanId-01"

  @Test
  fun `parse reads the four fields`() {
    val parsed = TraceContext.parse(validHeader)!!
    assertEquals(validTraceId, parsed.traceId)
    assertEquals(validSpanId, parsed.spanId)
    assertTrue(parsed.sampled)
  }

  @Test
  fun `the sampled bit is read from the flags byte`() {
    assertFalse(TraceContext.parse("00-$validTraceId-$validSpanId-00")!!.sampled)
    // Only bit 0 is defined; the reserved bits must not change the answer.
    assertTrue(TraceContext.parse("00-$validTraceId-$validSpanId-03")!!.sampled)
    assertFalse(TraceContext.parse("00-$validTraceId-$validSpanId-02")!!.sampled)
  }

  @Test
  fun `toHeader round-trips`() {
    assertEquals(validHeader, TraceContext.parse(validHeader)!!.toHeader())
    assertEquals(
      "00-$validTraceId-$validSpanId-00",
      TraceContext(validTraceId, validSpanId, sampled = false).toHeader(),
    )
  }

  @Test
  fun `an absent header is not an error`() {
    assertNull(TraceContext.parse(null))
    assertNull(TraceContext.parse(""))
    assertNull(TraceContext.parse("   "))
  }

  @Test
  fun `all-zero ids are rejected`() {
    // The spec singles these out: they are the "no trace" sentinel, not an identifier.
    assertNull(TraceContext.parse("00-${"0".repeat(32)}-$validSpanId-01"))
    assertNull(TraceContext.parse("00-$validTraceId-${"0".repeat(16)}-01"))
  }

  @Test
  fun `uppercase hex is rejected`() {
    // Accepting it would let two spellings of the same trace look like two traces.
    assertNull(TraceContext.parse("00-${validTraceId.uppercase()}-$validSpanId-01"))
    assertNull(TraceContext.parse("00-$validTraceId-${validSpanId.uppercase()}-01"))
  }

  @Test
  fun `malformed headers are treated as absent`() {
    assertNull(TraceContext.parse("00-$validTraceId-$validSpanId"))
    assertNull(TraceContext.parse("00-${validTraceId.dropLast(1)}-$validSpanId-01"))
    assertNull(TraceContext.parse("00-$validTraceId-${validSpanId}ab-01"))
    assertNull(TraceContext.parse("00-$validTraceId-$validSpanId-0z"))
    assertNull(TraceContext.parse("not-a-traceparent"))
    // Version ff is invalid by definition.
    assertNull(TraceContext.parse("ff-$validTraceId-$validSpanId-01"))
    // A version-00 header carries exactly four fields.
    assertNull(TraceContext.parse("00-$validTraceId-$validSpanId-01-extra"))
  }

  @Test
  fun `an unknown future version still yields the trace id`() {
    // Being strict here would break the chain the moment something upstream upgrades.
    val parsed = TraceContext.parse("01-$validTraceId-$validSpanId-01-something")!!
    assertEquals(validTraceId, parsed.traceId)
  }

  @Test
  fun `newChild keeps the journey and starts a new hop`() {
    val parent = TraceContext.parse(validHeader)!!
    val child = parent.newChild()
    assertEquals(parent.traceId, child.traceId)
    assertNotEquals(parent.spanId, child.spanId)
    assertEquals(parent.sampled, child.sampled)
  }

  @Test
  fun `random produces parseable, distinct, sampled contexts`() {
    val ids = (1..200).map { TraceContext.random() }
    ids.forEach { context ->
      assertEquals(context, TraceContext.parse(context.toHeader()))
      assertTrue(context.sampled, "nothing samples here, so generated traces must be recordable")
    }
    assertEquals(200, ids.map { it.traceId }.toSet().size)
  }
}
