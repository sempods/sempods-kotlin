package org.sempods.pods

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The resource boundary invariant: a model may only carry statements about the resource itself,
 * and blank nodes are forbidden (pod data is fully IRI/literal-valued).
 */
class ResourceBoundaryTest {

  private val resource = Values.iri("https://pod.example/events/summer-party")
  private val context = Values.iri("https://pod.example/main")
  private val otherContext = Values.iri("https://pod.example/other")
  private val event = Values.iri("https://schema.org/Event")
  private val name = Values.iri("https://schema.org/name")
  private val location = Values.iri("https://schema.org/location")

  @Test
  fun `a clean own-subject resource passes, across multiple contexts`() {
    val model = LinkedHashModel().apply {
      add(resource, RDF.TYPE, event, context)
      add(resource, name, Values.literal("Summer Party"), context)
      add(resource, name, Values.literal("Sommerfest"), otherContext)
      // IRI object (a linked resource) is fine — that is the LOD way.
      add(resource, location, Values.iri("https://pod.example/places/stadtpark"), context)
    }
    ResourceBoundary.requireWithinBoundary(resource, model) // must not throw
  }

  @Test
  fun `an empty model passes`() {
    ResourceBoundary.requireWithinBoundary(resource, LinkedHashModel())
  }

  @Test
  fun `a foreign named subject is rejected`() {
    val model = LinkedHashModel().apply {
      add(resource, RDF.TYPE, event, context)
      add(Values.iri("https://pod.example/places/stadtpark"), name, Values.literal("Stadtpark"), context)
    }
    assertFailsWith<IllegalArgumentException> { ResourceBoundary.requireWithinBoundary(resource, model) }
  }

  @Test
  fun `a blank-node object is rejected`() {
    val model = LinkedHashModel().apply {
      add(resource, RDF.TYPE, event, context)
      add(resource, location, Values.bnode(), context)
    }
    assertFailsWith<IllegalArgumentException> { ResourceBoundary.requireWithinBoundary(resource, model) }
  }

  @Test
  fun `a blank-node subject is rejected`() {
    val model = LinkedHashModel().apply {
      add(resource, RDF.TYPE, event, context)
      add(Values.bnode(), name, Values.literal("nested"), context)
    }
    assertFailsWith<IllegalArgumentException> { ResourceBoundary.requireWithinBoundary(resource, model) }
  }
}
