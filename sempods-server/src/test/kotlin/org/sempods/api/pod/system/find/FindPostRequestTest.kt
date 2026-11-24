package org.sempods.api.pod.system.find

import org.junit.jupiter.api.Test
import org.sempods.commons.json.JsonMappers
import kotlin.test.assertEquals

class FindPostRequestTest {

  @Test
  fun `what the DTO writes with the project's mapper is what the route reads back`() {
    listOf(
      FindPostRequest(text = "harbour"),
      FindPostRequest(text = "harbour", includeContexts = true),
      FindPostRequest(
        text = "harbour",
        type = listOf("https://schema.org/Article"),
        contexts = emptyList(),
        includeContexts = false,
        limit = 3,
      ),
    ).forEach { request ->
      val written = JsonMappers.default().writeValueAsString(request)
      assertEquals(request, FindPostRequest.parse(written), written)
    }
  }

  @Test
  fun `the DTO writes the route's member names and leaves unset members out`() {
    assertEquals(
      """{"text":"harbour","include_contexts":true}""",
      JsonMappers.default().writeValueAsString(FindPostRequest(text = "harbour", includeContexts = true)),
    )
  }
}
