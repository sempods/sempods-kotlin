package org.sempods.commons.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class LogSafeTextTest {

  @Test fun `ordinary text is returned untouched`() {
    assertEquals("did:web:apps.sempods.org:chat", LogSafeText.of("did:web:apps.sempods.org:chat"))
    assertEquals("", LogSafeText.of(""))
  }

  @Test fun `a newline cannot end the line early`() {
    // The whole point: a value carrying a line break would otherwise write the next log line
    // itself, and a forged line is indistinguishable from a real one afterwards.
    assertEquals(
      "dyn:abc\\u000a2026-01-01 WARN forged",
      LogSafeText.of("dyn:abc\n2026-01-01 WARN forged"),
    )
    assertEquals("a\\u000db", LogSafeText.of("a\rb"))
    assertEquals("a\\u0000b", LogSafeText.of("a\u0000b"))
  }

  @Test fun `the Unicode separators are escaped too`() {
    // Neither is a control character nor whitespace to `Character`, both pass every URI check,
    // and plenty of log viewers treat U+2028 as a line break.
    assertEquals("a\\u2028b", LogSafeText.of("a\u2028b"))
    assertEquals("a\\u2029b", LogSafeText.of("a\u2029b"))
  }

  @Test fun `C1 controls and NEL are covered`() {
    assertEquals("a\\u0085b", LogSafeText.of("a\u0085b"))
    assertEquals("a\\u009bb", LogSafeText.of("a\u009bb"))
  }

  @Test fun `ordinary spaces and non-ASCII text stay as they are`() {
    assertEquals("two words", LogSafeText.of("two words"))
    assertEquals("Gruesse - ok", LogSafeText.of("Gruesse - ok"))
  }
}
