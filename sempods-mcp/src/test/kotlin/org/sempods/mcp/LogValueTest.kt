package org.sempods.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [forLog] is what keeps a value this service did not author from writing its own log entry. The
 * console pattern ends with `%msg%n` and passes the message through unchanged, so a newline inside
 * an interpolated value reads as a second event — with a timestamp and a level its author chose.
 *
 * Both sources are covered: request parameters at the OAuth surface, and pod-authored text on the
 * client side — where nimbus quotes a rejected URI back inside its own exception message.
 */
class LogValueTest {

  @Test
  fun `a newline cannot start a second log line`() {
    val forged = "access_denied\n2026-08-20T12:00:00 ERROR [main] [] o.s.Fake - pod deleted by admin"
    val logged = forLog(forged)

    assertFalse('\n' in logged, logged)
    assertFalse('\r' in logged, logged)
    assertTrue(logged.startsWith("access_denied."), logged)
  }

  @Test
  fun `control characters are replaced rather than dropped, so the length still reflects what arrived`() {
    assertEquals("a..b c", forLog("a\r\nb c"), "a space is not a control character and carries no risk")
    assertEquals("tab.here", forLog("tab\there"))
    assertEquals("", forLog(""))
    assertEquals("none", forLog(null), "a null reads as a word, so a caller need not choose")
  }

  @Test
  fun `an ordinary value passes through untouched`() {
    assertEquals("invalid_grant", forLog("invalid_grant"))
    assertEquals("dyn:AbC-123_x", forLog("dyn:AbC-123_x"))
    // Non-ASCII is not a control character and carries diagnostic value.
    assertEquals("fehlgeschlägen", forLog("fehlgeschlägen"))
  }

  @Test
  fun `an oversized value is capped and marked`() {
    val logged = forLog("x".repeat(5_000))
    assertEquals(201, logged.length, "200 characters plus the ellipsis")
    assertTrue(logged.endsWith("…"), logged)
  }

  @Test
  fun `a nimbus exception message quoting a hostile URI cannot forge a line`() {
    // Verified against the SDK: `AuthorizationServerMetadata.parse` answers a control-character
    // issuer with `ParseException: Illegal character in path at index 17: <the value, verbatim>`.
    val nimbusMessage = "Illegal character in path at index 17: https://pod.test/\r\n2026-08-20 ERROR forged"
    val logged = forLog(nimbusMessage)

    assertFalse('\n' in logged, logged)
    assertFalse('\r' in logged, logged)
    assertTrue(logged.startsWith("Illegal character in path"), logged)
  }

  @Test
  fun `a value exactly at the cap is not marked as truncated`() {
    assertEquals("x".repeat(200), forLog("x".repeat(200)))
  }
}
