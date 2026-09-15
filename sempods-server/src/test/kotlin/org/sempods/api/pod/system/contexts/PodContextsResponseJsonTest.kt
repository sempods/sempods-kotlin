package org.sempods.api.pod.system.contexts

import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The listing class as JSON, through the server's own mapper: both spellings written, and a listing in
 * the earlier spelling still read.
 */
class PodContextsResponseJsonTest {

  private val jsonUtil = JsonUtil(JsonMappers.default())

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private val listing = PodContextsListResponse(
    podBaseUrl = "https://pods.example/alice",
    authenticated = false,
    contexts = listOf(
      PodContextResponse(
        contextIri = tasks,
        permissions = listOf("read"),
        source = "public",
        label = null,
        description = null,
        public = true,
        createdAt = "2026-05-20T10:15:30Z",
      ),
    ),
    writableContexts = listOf(tasks),
  )

  @Test
  fun `a listing in the earlier member names reads as it did`() {
    val earlier = """{"pod_base_url":"https://pods.example/alice","authenticated":false,""" +
      """"contexts":[{"context_iri":"$tasks","permissions":["read"],"source":"public","public":true,""" +
      """"createdAt":"2026-05-20T10:15:30Z"}],"writable_contexts":["$tasks"]}"""

    assertEquals(listing, jsonUtil.read(earlier, PodContextsListResponse::class.java))
  }

  @Test
  fun `a listing writes both spellings and reads back as itself`() {
    val written = jsonUtil.write(listing)

    assertEquals(
      setOf("podBaseUrl", "pod_base_url", "authenticated", "contexts", "writableContexts", "writable_contexts"),
      jsonUtil.read(written).keys,
    )
    assertEquals(listing, jsonUtil.read(written, PodContextsListResponse::class.java))
  }
}
