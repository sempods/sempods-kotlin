package org.sempods.client

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** What a selection holds, and what it refuses to hold. */
class SempodsContextSelectionTest {

  @Test
  fun `readable restricts nothing, and none and of restrict`() {
    assertFalse(SempodsContextSelection.readable().isRestricted)
    assertTrue(SempodsContextSelection.none().isRestricted)
    assertEquals(emptyList(), SempodsContextSelection.none().contextUris)
    assertEquals(listOf("urn:a"), SempodsContextSelection.of("urn:a").contextUris)
  }

  @Test
  fun `a selection given nothing is none, never readable`() {
    assertEquals(SempodsContextSelection.none(), SempodsContextSelection.of())
    assertEquals(SempodsContextSelection.none(), SempodsContextSelection.of(emptyList()))
    assertNotEquals(SempodsContextSelection.readable(), SempodsContextSelection.of())
  }

  @Test
  fun `a repeated IRI counts once, and the order first given is kept`() {
    assertEquals(listOf("urn:b", "urn:a"), SempodsContextSelection.of("urn:b", "urn:a", "urn:b").contextUris)
    assertEquals(SempodsContextSelection.of("urn:b", "urn:a"), SempodsContextSelection.of(listOf("urn:b", "urn:a", "urn:a")))
  }

  @ParameterizedTest
  @ValueSource(strings = ["", " ", "\t"])
  fun `a blank IRI is refused by its position`(blank: String) {
    val refused = assertThrows<IllegalArgumentException> { SempodsContextSelection.of("urn:a", blank) }

    assertEquals("Context IRI 1 of the selection is blank. Leave it out, or select none() to match nothing.", refused.message)
  }

  @Test
  fun `the IRIs cannot be changed through the list`() {
    val selection = SempodsContextSelection.of("urn:a")

    assertThrows<UnsupportedOperationException> { (selection.contextUris as MutableList<String>).add("urn:b") }
  }

  @Test
  fun `equality and text follow what is selected`() {
    assertEquals(SempodsContextSelection.of("urn:a").hashCode(), SempodsContextSelection.of(listOf("urn:a")).hashCode())
    assertEquals("SempodsContextSelection(readable)", SempodsContextSelection.readable().toString())
    assertEquals("SempodsContextSelection(none)", SempodsContextSelection.none().toString())
    assertEquals("SempodsContextSelection([urn:a, urn:b])", SempodsContextSelection.of("urn:a", "urn:b").toString())
  }
}
