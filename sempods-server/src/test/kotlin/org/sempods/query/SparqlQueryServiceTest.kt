package org.sempods.query

import org.sempods.commons.json.JsonMappers
import org.sempods.pods.PodRepositoryCache
import io.mockk.every
import io.mockk.mockk
import org.sempods.commons.jaxrs.errors.ApiException
import org.sempods.rdf.stream.write
import org.eclipse.rdf4j.rio.RDFFormat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SparqlQueryServiceTest {

  private val service = SparqlQueryService(mockk<PodRepositoryCache>(relaxed = true))
  private val objectMapper = JsonMappers.default()

  @Test
  fun `empty store ASK preserves data-independent truth`() {
    assertTrue(
      service.executeBooleanAsJsonOnEmptyStore("ASK WHERE { BIND(1 AS ?x) }").contains("\"boolean\":true")
    )
    assertTrue(
      service.executeBooleanAsJsonOnEmptyStore("ASK WHERE { FILTER(true) }").contains("\"boolean\":true")
    )
    assertTrue(
      service.executeBooleanAsJsonOnEmptyStore("ASK { ?s ?p ?o }").contains("\"boolean\":false")
    )
  }

  @Test
  fun `empty store SELECT uses RDF4J result writer`() {
    val json = service.executeTupleAsJsonOnEmptyStore("SELECT ?b ?a WHERE { ?s ?p ?o }")
    val root = objectMapper.readValue(json, Map::class.java)
    val head = root["head"] as Map<*, *>
    val results = root["results"] as Map<*, *>

    assertEquals(listOf("b", "a"), head["vars"])
    assertTrue((results["bindings"] as List<*>).isEmpty())
  }

  @Test
  fun `empty store CONSTRUCT writes the data-independent template`() {
    val nQuads = service.executeGraphAsStringOnEmptyStore(
      "CONSTRUCT { <urn:s> <urn:p> \"o\" } WHERE {}",
      RDFFormat.NQUADS,
    )

    assertEquals("<urn:s> <urn:p> \"o\" .", nQuads.trim())
  }

  @Test
  fun `a graph source reaches the pod only when it is written`() {
    val cache = mockk<PodRepositoryCache>()
    every { cache.get(any()) } returns null
    val source = SparqlQueryService(cache).graphSource(pod = "missing", query = "CONSTRUCT WHERE { ?s ?p ?o }", dataset = null)

    assertThrows<ApiException> { source.write(RDFFormat.NQUADS, ByteArrayOutputStream()) }
  }
}
