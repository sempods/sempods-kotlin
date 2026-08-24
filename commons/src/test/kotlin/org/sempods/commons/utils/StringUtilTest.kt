package org.sempods.commons.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [StringUtil.parseList] and [StringUtil.parseSet] replaced a Guava `Splitter` chain. These cases
 * pin the behaviour that chain had, because query parameters on live endpoints are parsed with it.
 */
class StringUtilTest {

  @Test
  fun `parseList splits on commas`() {
    assertEquals(listOf("a", "b", "c"), StringUtil.parseList("a,b,c"))
  }

  @Test
  fun `parseList trims each item`() {
    assertEquals(listOf("a", "b"), StringUtil.parseList(" a , b "))
  }

  @Test
  fun `parseList drops empty items`() {
    assertEquals(listOf("a", "b"), StringUtil.parseList("a,,b,"))
  }

  @Test
  fun `parseList is null for absent or blank input`() {
    assertNull(StringUtil.parseList(null))
    assertNull(StringUtil.parseList(""))
    assertNull(StringUtil.parseList("   "))
  }

  @Test
  fun `parseList of only separators is empty rather than null`() {
    // "given, but nothing in it" — distinct from "not given", which the endpoints act on.
    assertEquals(emptyList(), StringUtil.parseList(","))
  }

  @Test
  fun `parseSet deduplicates`() {
    assertEquals(setOf("a", "b"), StringUtil.parseSet("a,b,a"))
  }

  @Test
  fun `parseSet is null for absent input`() {
    assertNull(StringUtil.parseSet(null))
  }
}
