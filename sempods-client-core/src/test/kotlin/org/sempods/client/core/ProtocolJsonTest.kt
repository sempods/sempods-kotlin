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
