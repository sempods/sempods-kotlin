package org.sempods.client.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** A protocol object's members, below the routes that read them. */
class ProtocolJsonTest {

  private fun document(json: String) = decodeObject(json.toByteArray())

  @Test
  fun `a string member is its value, and a null or missing one is null`() {
    val document = document("""{"a":"x","b":null}""")

    assertEquals("x", document.stringOrNull("a"))
    assertNull(document.stringOrNull("b"))
    assertNull(document.stringOrNull("c"))
  }

  @ParameterizedTest
  @ValueSource(strings = ["1", "true", "{}", "[]"])
  fun `a member of another type is a violation that names it and its type`(value: String) {
    val violation = assertThrows<ProtocolViolation> { document("""{"a":$value}""").stringOrNull("a") }

    assertTrue(violation.detail.startsWith("/a: expected a string or null, found "), violation.detail)
  }

  @Test
  fun `a required member names what it found when it is missing, null or of another type`() {
    val document = document("""{"s":"x","b":true,"n":null,"o":{}}""")

    assertEquals("x", document.string("s"))
    assertEquals(true, document.boolean("b"))
    assertEquals("/missing: expected a string, found no value", assertThrows<ProtocolViolation> { document.string("missing") }.detail)
    assertEquals("/n: expected a boolean, found null", assertThrows<ProtocolViolation> { document.boolean("n") }.detail)
    assertEquals("/s: expected an object, found string", assertThrows<ProtocolViolation> { document.nested("s") }.detail)
    assertEquals("/o: expected an array of strings, found object", assertThrows<ProtocolViolation> { document.strings("o") }.detail)
    assertEquals("/n: expected an array of objects, found null", assertThrows<ProtocolViolation> { document.objects("n") }.detail)
  }

  @Test
  fun `an array reads as its elements, and a violation inside one names the element by its pointer, escaped`() {
    val document = document("""{"a/b":[{"c~d":["x",true]}],"e":[]}""")

    assertEquals(emptyList(), document.strings("e"))
    assertEquals(
      "/a~1b/0/c~0d/1: expected a string, found boolean",
      assertThrows<ProtocolViolation> { document.objects("a/b").single().strings("c~d") }.detail,
    )
    assertEquals(
      "/o/0: expected an object, found null",
      assertThrows<ProtocolViolation> { document("""{"o":[null]}""").objects("o") }.detail,
    )
  }

  @Test
  fun `member names come in document order`() {
    assertEquals(listOf("z", "a", "m"), document("""{"z":1,"a":2,"m":3}""").names())
  }

  @Test
  fun `a member the document named is located by the reading's label, never by its name`() {
    val row = document("""{"rows":[{"secret-name":{"k":1}}]}""").objects("rows").single()
    val cell = row.nestedNamed(row.names().single(), "row 0, cell 0")

    assertEquals("row 0, cell 0, member k: expected a string, found number", assertThrows<ProtocolViolation> { cell.string("k") }.detail)
    assertEquals("/rows/0: breaks a rule", row.violation("breaks a rule").detail)
    assertEquals(
      "row 0, cell 0: expected an object, found string",
      assertThrows<ProtocolViolation> { document("""{"secret-name":"v"}""").nestedNamed("secret-name", "row 0, cell 0") }.detail,
    )
  }

  @Test
  fun `a document that is not one object says what it found`() {
    assertEquals(
      "the document: expected an object, found array",
      assertThrows<ProtocolViolation> { document("[]") }.detail,
    )
  }

  @Test
  fun `a malformed document is located and not quoted`() {
    val violation = assertThrows<ProtocolViolation> { document("""{"a": secret}""") }

    assertTrue(violation.detail.startsWith("malformed JSON at line 1, column "), violation.detail)
    assertFalse("secret" in violation.detail, violation.detail)
    assertNull(violation.cause)
  }
}
