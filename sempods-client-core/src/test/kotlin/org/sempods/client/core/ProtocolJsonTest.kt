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
  fun `a required string names what it found when it is missing or null`() {
    assertEquals("/a: expected a string, found no value", assertThrows<ProtocolViolation> { document("{}").string("a") }.detail)
    assertEquals(
      "/a: expected a string, found null",
      assertThrows<ProtocolViolation> { document("""{"a":null}""").string("a") }.detail,
    )
  }

  @Test
  fun `a boolean member is its value, a null or missing one is null, and a string is not a boolean`() {
    val document = document("""{"t":true,"f":false,"n":null,"s":"true"}""")

    assertEquals(true, document.booleanOrNull("t"))
    assertEquals(false, document.booleanOrNull("f"))
    assertNull(document.booleanOrNull("n"))
    assertNull(document.booleanOrNull("missing"))
    assertEquals(
      "/s: expected a boolean or null, found string",
      assertThrows<ProtocolViolation> { document.booleanOrNull("s") }.detail,
    )
  }

  @Test
  fun `an array reads as its elements, an empty one as empty, and a null or missing one as null`() {
    val document = document("""{"o":[{"s":"x"},{}],"s":["a","b"],"e":[],"n":null}""")

    assertEquals(listOf("x", null), document.objectsOrNull("o")?.map { it.stringOrNull("s") })
    assertEquals(listOf("a", "b"), document.stringsOrNull("s"))
    assertEquals(emptyList(), document.stringsOrNull("e"))
    assertNull(document.objectsOrNull("n"))
    assertNull(document.stringsOrNull("missing"))
  }

  @Test
  fun `a violation inside an array names the element by its pointer, with the member names escaped`() {
    val nested = document("""{"a/b":[{"c~d":["x",true]}]}""").objectsOrNull("a/b")?.single()

    assertEquals(
      "/a~1b/0/c~0d/1: expected a string, found boolean",
      assertThrows<ProtocolViolation> { nested?.stringsOrNull("c~d") }.detail,
    )
    assertEquals(
      "/o/0: expected an object, found null",
      assertThrows<ProtocolViolation> { document("""{"o":[null]}""").objectsOrNull("o") }.detail,
    )
  }

  @Test
  fun `an encoded object keeps its members' order and escapes what JSON requires`() {
    val encoded = encodeObject(linkedMapOf<String, Any>("z" to "\"\\\n\u0000", "a" to true))

    assertEquals("{\"z\":\"\\\"\\\\\\n\\u0000\",\"a\":true}", String(encoded, Charsets.UTF_8))
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
