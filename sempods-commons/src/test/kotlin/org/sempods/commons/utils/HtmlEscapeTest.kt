package org.sempods.commons.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HtmlEscapeTest {

  @Test
  fun `escapes all five characters`() {
    assertEquals("&amp;&lt;&gt;&quot;&#39;", "&<>\"'".escapeHtml())
  }

  @Test
  fun `escapes the ampersand of an entity it just wrote`() {
    // The order the loop runs in is what makes this true: escaping `<` first and `&` afterwards
    // would turn `&lt;` into `&amp;lt;`. A character-at-a-time pass cannot make that mistake, and
    // this case is what says so.
    assertEquals("&amp;lt;", "&lt;".escapeHtml())
  }

  @Test
  fun `leaves everything else alone`() {
    val untouched = "Alice Smith — café ☕ / 100% ok"
    assertEquals(untouched, untouched.escapeHtml())
  }

  @Test
  fun `closes a single-quoted attribute value`() {
    // The four-character variant this replaced let `'` through, so a value interpolated into
    // `href='…'` could end the attribute and start another one.
    assertEquals("a&#39; onclick=&#39;x", "a' onclick='x".escapeHtml())
  }

  @Test
  fun `appends to a builder and returns it for chaining`() {
    val html = StringBuilder("<p>").appendEscapedHtml("a<b").append("</p>").toString()
    assertEquals("<p>a&lt;b</p>", html)
  }

  @Test
  fun `empty input stays empty`() {
    assertEquals("", "".escapeHtml())
  }
}
