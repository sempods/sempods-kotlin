package org.sempods.commons.mongo

import org.bson.Document
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DocumentsTest {

  @Test
  fun `a null value is omitted rather than written as BSON null`() {
    val doc = Document().putNotNull("a", null).putNotNull("b", "x")
    assertFalse(doc.containsKey("a"), doc.toJson())
    assertEquals("x", doc.getString("b"))
  }

  @Test
  fun `a null instant is omitted`() {
    assertFalse(Document().putInstant("at", null).containsKey("at"))
  }

  @Test
  fun `an instant is stored as a BSON date`() {
    val at = Instant.parse("2026-08-09T10:15:30Z")
    val doc = Document().putInstant("at", at)
    assertEquals(Date.from(at), doc.getDate("at"))
    assertEquals(at, doc.getInstant("at"))
  }

  @Test
  fun `an instant is truncated to milliseconds, not rounded`() {
    // The nanosecond half of the value has nowhere to go in BSON. Truncation (not rounding) is
    // what java.util.Date does and therefore what Morphia stores.
    val doc = Document().putInstant("at", Instant.parse("2026-08-09T10:15:30.123999999Z"))
    assertEquals(Instant.parse("2026-08-09T10:15:30.123Z"), doc.getInstant("at"))
  }

  @Test
  fun `getInstant is null for an absent field`() {
    assertNull(Document().getInstant("nope"))
  }

  @Test
  fun `a null or empty collection is omitted`() {
    assertFalse(Document().putStrings("tags", null).containsKey("tags"))
    assertFalse(Document().putStrings("tags", emptySet()).containsKey("tags"))
    assertFalse(Document().putStrings("tags", emptyList()).containsKey("tags"))
  }

  @Test
  fun `strings are stored as an array in iteration order`() {
    val doc = Document().putStrings("tags", linkedSetOf("b", "a", "c"))
    assertEquals(listOf("b", "a", "c"), doc.getStringList("tags"))
  }

  @Test
  fun `a stored array reads back as a set`() {
    val doc = Document().putStrings("tags", setOf("read", "write"))
    assertEquals(setOf("read", "write"), doc.getStringSet("tags"))
  }

  @Test
  fun `an absent array reads back as empty rather than null`() {
    // This is what makes omitting an empty collection safe: the reader cannot tell "written as
    // empty" from "not written".
    assertEquals(emptySet(), Document().getStringSet("tags"))
    assertEquals(emptyList(), Document().getStringList("tags"))
  }

  @Test
  fun `a round trip through an empty collection is stable`() {
    val doc = Document().putStrings("tags", emptySet())
    assertEquals(emptySet(), doc.getStringSet("tags"))
  }
}
