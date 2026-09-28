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
  private val validState = "congo=t61rcWkgMzE,rojo=00f067aa0ba902b7"

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
    // The other bits must not change the answer.
    assertTrue(TraceContext.parse("00-$validTraceId-$validSpanId-03")!!.sampled)
    assertFalse(TraceContext.parse("00-$validTraceId-$validSpanId-02")!!.sampled)
  }

  @Test
  fun `the random trace-id bit travels and the reserved bits leave as zero`() {
    assertTrue(TraceContext.parse("00-$validTraceId-$validSpanId-02")!!.randomTraceId)
    assertFalse(TraceContext.parse(validHeader)!!.randomTraceId)
    assertEquals("00-$validTraceId-$validSpanId-03", TraceContext.parse("00-$validTraceId-$validSpanId-03")!!.toHeader())
    assertEquals("00-$validTraceId-$validSpanId-03", TraceContext.parse("00-$validTraceId-$validSpanId-ff")!!.toHeader())
    assertEquals("00-$validTraceId-$validSpanId-00", TraceContext.parse("00-$validTraceId-$validSpanId-fc")!!.toHeader())
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
    val parent = TraceContext.parse("00-$validTraceId-$validSpanId-03", validState)!!
    val child = parent.newChild()
    assertEquals(parent.traceId, child.traceId)
    assertNotEquals(parent.spanId, child.spanId)
    assertEquals(parent.sampled, child.sampled)
    assertEquals(parent.randomTraceId, child.randomTraceId)
    assertEquals(validState, child.traceState)
  }

  @Test
  fun `a valid tracestate is kept as it arrived`() {
    assertEquals(validState, TraceContext.parse(validHeader, validState)!!.traceState)
    assertEquals("fw529a3039@dt=FzA0MTI", TraceContext.parse(validHeader, "fw529a3039@dt=FzA0MTI")!!.traceState)
    // A value may start with a space; only its last character may not be one.
    assertEquals("congo= t61rcWkgMzE", TraceContext.parse(validHeader, "congo= t61rcWkgMzE")!!.traceState)
  }

  @Test
  fun `white space around entries and empty entries are dropped`() {
    assertEquals(
      validState,
      TraceContext.parse(validHeader, " congo=t61rcWkgMzE ,\t,rojo=00f067aa0ba902b7, ")!!.traceState,
    )
    assertNull(TraceContext.parse(validHeader, " , ")!!.traceState)
    assertNull(TraceContext.parse(validHeader, "")!!.traceState)
  }

  @Test
  fun `an unparseable tracestate is discarded as a whole and the traceparent stays`() {
    val unparseable = listOf(
      "congo=t61rcWkgMzE,Rojo=1", // uppercase key
      "congo=t61rcWkgMzE,rojo", // no value
      "congo=t61rcWkgMzE,rojo=", // empty value
      "congo=t61rcWkgMzE,rojo=a=b", // "=" inside the value
      "congo =t61rcWkgMzE", // white space before "=": the grammar has none around it
      "congo=1,congo=2", // a key twice
      (1..33).joinToString(",") { "k$it=v" }, // more than 32 entries
    )
    for (state in unparseable) {
      val parsed = TraceContext.parse(validHeader, state)!!
      assertEquals(validTraceId, parsed.traceId)
      assertNull(parsed.traceState, "expected '$state' to be discarded")
    }
    val thirtyTwo = (1..32).joinToString(",") { "k$it=v" }
    assertEquals(thirtyTwo, TraceContext.parse(validHeader, thirtyTwo)!!.traceState)
  }

  @Test
  fun `a tracestate over 512 characters loses long entries first, then entries from the end`() {
    // 24 entries of 20 characters and their commas: 503 characters.
    val fitting = (10..33).map { "k$it=" + "v".repeat(16) }
    val long = "long=" + "x".repeat(150)
    assertEquals(
      fitting.joinToString(","),
      TraceContext.parse(validHeader, (listOf(long) + fitting).joinToString(","))!!.traceState,
    )

    val tooMany = (10..40).map { "k$it=" + "v".repeat(16) }
    assertEquals(
      tooMany.take(24).joinToString(","),
      TraceContext.parse(validHeader, tooMany.joinToString(","))!!.traceState,
    )
  }

  @Test
  fun `random produces parseable, distinct, sampled contexts`() {
    val ids = (1..200).map { TraceContext.random() }
    ids.forEach { context ->
      assertEquals(context, TraceContext.parse(context.toHeader()))
      assertTrue(context.sampled, "nothing samples here, so generated traces must be recordable")
      assertTrue(context.randomTraceId, "every bit of a generated trace id is random")
    }
    assertEquals(200, ids.map { it.traceId }.toSet().size)
  }
}
