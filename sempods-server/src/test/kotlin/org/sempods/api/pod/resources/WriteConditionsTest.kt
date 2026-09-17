package org.sempods.api.pod.resources

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.EntityTag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Docker-free unit test of [WriteConditions]: the field grammar and the two comparisons. */
class WriteConditionsTest {

  private val current = listOf(EntityTag("a-jsonld"), EntityTag("a-nquads"))

  private fun status(ifMatch: String? = null, ifNoneMatch: String? = null, against: List<EntityTag> = current): Int =
    try {
      WriteConditions(ifMatch, ifNoneMatch).requireHold(against)
      200
    } catch (e: WebApplicationException) {
      e.response.status
    }

  @Test
  fun `no condition holds whatever the target is`() {
    assertEquals(200, status())
    assertEquals(200, status(against = emptyList()))
  }

  @Test
  fun `If-Match holds for any current tag, and star for any representation`() {
    assertEquals(200, status(ifMatch = "\"a-nquads\""))
    assertEquals(200, status(ifMatch = "\"b\", \"a-jsonld\""))
    assertEquals(412, status(ifMatch = "\"b\""))
    assertEquals(200, status(ifMatch = "*"))
    assertEquals(412, status(ifMatch = "*", against = emptyList()))
    assertEquals(412, status(ifMatch = "\"a-jsonld\"", against = emptyList()))
  }

  @Test
  fun `If-Match compares strongly and If-None-Match weakly`() {
    assertEquals(412, status(ifMatch = "W/\"a-jsonld\""))
    assertEquals(412, status(ifNoneMatch = "W/\"a-jsonld\""))
    assertEquals(200, status(ifNoneMatch = "\"b\""))
    assertEquals(412, status(ifNoneMatch = "*"))
    assertEquals(200, status(ifNoneMatch = "*", against = emptyList()))
  }

  @Test
  fun `a list may carry whitespace and empty elements`() {
    // RFC 9110 §5.6.1: a recipient accepts empty list elements.
    assertEquals(200, status(ifMatch = " , \"b\" ,, \t\"a-jsonld\",  "))
    // An empty list is a list: no tag of it matches.
    assertEquals(412, status(ifMatch = ""))
    assertEquals(200, status(ifNoneMatch = " , "))
  }

  @Test
  fun `a field that is not star or a list of entity tags is refused`() {
    for (field in listOf("a-jsonld", "\"a-jsonld", "\"a\" \"b\"", "\"a\", *", "W/a", "\"a\"b\"")) {
      val refused = assertFailsWith<WebApplicationException>(field) { WriteConditions(field, null).requireHold(current) }
      assertEquals(400, refused.response.status, field)
    }
    // Both fields are read before either is decided: a refusable If-None-Match is not hidden by a false If-Match.
    assertEquals(400, status(ifMatch = "\"b\"", ifNoneMatch = "nope"))
  }
}
