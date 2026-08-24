package org.sempods.retrieval

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Input-boundary validation for `find` requests, shared by the REST endpoint and the MCP tool. */
class FindRequestParserTest {

  @Test
  fun `rejects absent or whitespace-only text`() {
    assertFailsWith<IllegalArgumentException> { FindRequestParser.parse(null, emptyList(), null) }
    assertFailsWith<IllegalArgumentException> { FindRequestParser.parse("   ", emptyList(), null) }
  }

  @Test
  fun `rejects a type IRI carrying injection characters`() {
    // createIRI accepts this (lax), so the well-formedness guard must reject it before it reaches
    // query construction.
    assertFailsWith<IllegalArgumentException> {
      FindRequestParser.parse("alice", listOf("https://evil/a> } UNION { ?s ?p ?o"), null)
    }
  }

  @Test
  fun `parses a clean request, tokenizing and OR-collecting types`() {
    val request = FindRequestParser.parse(
      text = "  Alice   Bob ",
      typeIris = listOf("https://schema.org/Event", "  ", "https://schema.org/Person"),
      limit = 5,
    )
    assertEquals(listOf("alice", "bob"), request.tokens)
    assertEquals(2, request.types.size)
    assertEquals(5, request.limit)
  }

  @Test
  fun `defaults and clamps the limit`() {
    assertEquals(FindRequestParser.DEFAULT_LIMIT, FindRequestParser.parse("x", emptyList(), null).limit)
    assertEquals(FindRequestParser.MAX_LIMIT, FindRequestParser.parse("x", emptyList(), 9999).limit)
    assertEquals(1, FindRequestParser.parse("x", emptyList(), 0).limit)
  }
}
