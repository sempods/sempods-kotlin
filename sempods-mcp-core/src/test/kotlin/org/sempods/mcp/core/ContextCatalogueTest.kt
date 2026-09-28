package org.sempods.mcp.core

import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.sempods.commons.net.SempodsVocabulary

/** What the model is told it may reach, when the pod answers the catalogue as RDF. */
class ContextCatalogueTest {

  private val mapper = ObjectMapper()

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private val notes = "https://pods.example/alice/_system/contexts/notes"

  private val namedGraph = "http://www.w3.org/ns/sparql-service-description#namedGraph"

  private fun json(body: String) = mapper.readTree(body.trimIndent())

  @Test
  fun `a catalogue becomes the shape the tool has always had`() {
    val payload = ContextCatalogue.toToolPayload(
      json(
        """
        {
          "@id": "https://pods.example/alice/_system/contexts",
          "@type": ["http://www.w3.org/ns/sparql-service-description#GraphCollection"],
          "$namedGraph": [{"@id": "$tasks"}, {"@id": "$notes"}],
          "${SempodsVocabulary.READABLE_CONTEXT}": [{"@id": "$tasks"}, {"@id": "$notes"}],
          "${SempodsVocabulary.WRITABLE_CONTEXT}": [{"@id": "$tasks"}],
          "${SempodsVocabulary.MANAGEABLE_CONTEXT}": [{"@id": "$tasks"}]
        }
        """,
      ),
    )

    assertEquals(listOf(tasks, notes), payload.path("contexts").values().map { it.path("context_iri").asString() })
    assertEquals(listOf("read", "write", "manage"), payload.path("contexts")[0].path("permissions").values().map { it.asString() })
    assertEquals(listOf("read"), payload.path("contexts")[1].path("permissions").values().map { it.asString() })
    assertEquals(listOf(tasks), payload.path("writable_contexts").values().map { it.asString() })
  }

  @Test
  fun `an empty catalogue yields an empty listing`() {
    val payload = ContextCatalogue.toToolPayload(
      json(
        """
        {
          "@id": "https://pods.example/alice/_system/contexts",
          "@type": ["http://www.w3.org/ns/sparql-service-description#GraphCollection"]
        }
        """,
      ),
    )

    assertTrue(payload.path("contexts").isArray && payload.path("contexts").isEmpty)
    assertTrue(payload.path("writable_contexts").isArray && payload.path("writable_contexts").isEmpty)
  }

  @Test
  fun `a right stated about something the catalogue does not list invents no context`() {
    val payload = ContextCatalogue.toToolPayload(
      json(
        """
        {
          "@id": "https://pods.example/alice/_system/contexts",
          "$namedGraph": [{"@id": "$tasks"}],
          "${SempodsVocabulary.READABLE_CONTEXT}": [{"@id": "$tasks"}, {"@id": "$notes"}],
          "${SempodsVocabulary.WRITABLE_CONTEXT}": [{"@id": "$notes"}]
        }
        """,
      ),
    )

    assertEquals(listOf(tasks), payload.path("contexts").values().map { it.path("context_iri").asString() })
    assertEquals(listOf("read"), payload.path("contexts")[0].path("permissions").values().map { it.asString() })
    assertTrue(payload.path("writable_contexts").isEmpty)
  }

  @Test
  fun `the envelope of a pod that has not migrated is handed on as it arrived`() {
    val envelope = json("""{"contexts":[{"context_iri":"$tasks","permissions":["read"]}],"writable_contexts":[]}""")

    assertEquals(envelope, ContextCatalogue.toToolPayload(envelope))
  }
}
