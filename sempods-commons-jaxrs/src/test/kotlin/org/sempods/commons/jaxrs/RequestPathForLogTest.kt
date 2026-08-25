package org.sempods.commons.jaxrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * What may reach a log line from a request path.
 *
 * The path of a request refused during matching is attacker-controlled and arrives decoded, so
 * both halves here are about someone else's input: a credential the route declared, and characters
 * that would end the line early. Measured against a running server before this existed — Jetty
 * answers 400 to an ASCII control character in a URI, but `%E2%80%A8` reaches the provider intact,
 * which is why the two Unicode separators are named alongside `isISOControl`.
 *
 * Every special character below is written as an escape rather than pasted, so the fixture stays
 * readable in a diff and cannot be broken by an editor that normalises invisible characters.
 */
class RequestPathForLogTest {

  @Test
  fun `an ordinary path is returned as it came`() {
    val path = "v1/rooms/42/events/7"
    assertSame(path, RequestPathForLog.of(path), "no copy for the common case")
  }

  @Test
  fun `the segment after a declared marker is replaced`() {
    assertEquals(
      "v1/rooms/42/join/<redacted>",
      RequestPathForLog.of("v1/rooms/42/join/sh4re-t0ken", setOf("join")),
    )
  }

  @Test
  fun `only the segment that follows the marker goes`() {
    assertEquals(
      "join/<redacted>/join/<redacted>/tail",
      RequestPathForLog.of("join/one/join/two/tail", setOf("join")),
    )
  }

  @Test
  fun `a marker at the end of the path redacts nothing and throws nothing`() {
    assertEquals("v1/rooms/42/join", RequestPathForLog.of("v1/rooms/42/join", setOf("join")))
  }

  @Test
  fun `a newline cannot end the line early`() {
    val forged = RequestPathForLog.of("a\nERROR forged")
    assertFalse('\n' in forged, "was: $forged")
    assertEquals("a\\u000aERROR forged", forged)
  }

  @Test
  fun `the Unicode separators are escaped too`() {
    // The pair that actually gets through a container's URI check, and that log viewers and JSON
    // tooling read as a line break.
    assertEquals("a\\u2028b", RequestPathForLog.of("a\u2028b"))
    assertEquals("a\\u2029b", RequestPathForLog.of("a\u2029b"))
  }

  @Test
  fun `an escape sequence cannot reach a terminal`() {
    assertEquals("a\\u001b[2Kb", RequestPathForLog.of("a\u001b[2Kb"))
  }

  @Test
  fun `redaction happens before escaping, so a secret cannot hide behind a control character`() {
    assertEquals(
      "join/<redacted>",
      RequestPathForLog.of("join/t0ken\u2028more", setOf("join")),
    )
  }
}
