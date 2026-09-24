package org.sempods.client

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A caller's value added to a route as one path segment, and the values that cannot be one. */
class SempodsSessionSegmentsTest {

  private val session = SempodsSession(SempodsPodBase.of("https://pods.example/alice"))

  private fun path(route: String, vararg segments: String) =
    session.newRequest("GET", route, *segments).build().url.encodedPath

  @Test
  fun `each segment is encoded as one`() {
    assertEquals("/alice/_system/media/a%20b/%252e%252e", path("_system/media", "a b", "%2e%2e"))
  }

  @Test
  fun `an empty route leaves the segments directly under the pod`() {
    assertEquals("/alice/x", path("", "x"))
  }

  @Test
  fun `no segments address the route itself`() {
    assertEquals("/alice/_system/media", path("_system/media"))
  }

  @Test
  fun `a query on the route stays after the segments`() {
    val url = session.newRequest("GET", "items?view=compact", "abc").build().url
    assertEquals("/alice/items/abc", url.encodedPath)
    assertEquals("view=compact", url.encodedQuery)
  }

  @Test
  fun `a segment the URL would drop, collapse or split is refused`() {
    // Appended past the check, `..` addressed `/alice/_system` and `.` or `` addressed the route.
    listOf("..", ".", "", "a/b", "a\\b").forEach { segment ->
      val refused = assertThrows<IllegalArgumentException>(segment) { path("_system/media", segment) }
      assertEquals("'$segment' cannot be one path segment.", refused.message)
    }
  }
}
