package org.sempods.api.system.sparql

import org.junit.jupiter.api.Test
import jakarta.ws.rs.core.MediaType
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** Which graph format an `Accept` header gets on the `find` and SPARQL graph routes (#189). */
class GraphResultNegotiationTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource(
    delimiter = '|',
    nullValues = ["NONE"],
    value = [
      "NONE | JSON_LD",
      "*/* | JSON_LD",
      "application/n-quads | N_QUADS",
      "application/ld+json;q=0, application/n-quads | N_QUADS",
      "*/*, application/ld+json;q=0 | N_QUADS",
      "application/ld+json;q=0.4, application/n-quads;q=0.5 | N_QUADS",
      "application/n-quads, application/ld+json | JSON_LD",
      "application/*;q=0.2, application/n-quads;q=0.1 | JSON_LD",
      "application/json | NONE",
      "application/ld+json;q=0, application/n-quads;q=0 | NONE",
      "application/ld+json;profile=\"http://www.w3.org/ns/json-ld#expanded\" | NONE",
      "application/ld+json;charset=utf-8 | JSON_LD",
    ],
  )
  fun `quality decides, q=0 excludes, and a tie falls to JSON-LD`(accept: String?, expected: String?) {
    assertEquals(expected?.let(GraphResultFormat::valueOf), GraphResultNegotiation.select(accept))
  }

  @Test
  @Suppress("DEPRECATION")
  fun `the 0_2 list overload still answers as it did, first compatible range first`() {
    fun select(vararg ranges: String) = GraphResultNegotiation.select(ranges.map { MediaType.valueOf(it) })

    assertEquals(GraphResultFormat.JSON_LD, select())
    assertEquals(GraphResultFormat.N_QUADS, select("application/n-quads", "application/ld+json"))
    assertEquals(GraphResultFormat.JSON_LD, select("*/*"))
    assertEquals(null, select("application/json"))
  }
}
