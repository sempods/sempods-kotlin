package org.sempods.pods

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The resource boundary invariant: a model may only carry statements about the resource itself,
 * blank nodes are forbidden (pod data is fully IRI/literal-valued), and every IRI passes
 * [IriSyntax].
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

  @Test
  fun `an IRI outside RFC 3987 is rejected in every position`() {
    // `Values.iri` would refuse these itself; the lax factory is how a parser hands them over.
    val vf = SimpleValueFactory.getInstance()
    val bad = vf.createIRI("https://pod.example/a\uE000")
    val bySubject = vf.createIRI("https://pod.example/a\uFFFE")
    listOf(
      bySubject to LinkedHashModel().apply { add(bySubject, name, Values.literal("x"), context) },
      resource to LinkedHashModel().apply { add(resource, bad, Values.literal("x"), context) },
      resource to LinkedHashModel().apply { add(resource, location, bad, context) },
      resource to LinkedHashModel().apply { add(resource, name, vf.createLiteral("x", bad), context) },
      resource to LinkedHashModel().apply { add(resource, name, Values.literal("x"), bad) },
      resource to LinkedHashModel().apply { add(resource, location, vf.createIRI("/a:b"), context) },
    ).forEach { (subject, model) ->
      assertFailsWith<IllegalArgumentException>(model.toString()) { ResourceBoundary.requireWithinBoundary(subject, model) }
    }
  }

  @Test
  fun `an umlaut and a supplementary character pass`() {
    val model = LinkedHashModel().apply {
      add(resource, location, Values.iri("https://pod.example/places/M\u00FCnchen"), context)
      add(resource, location, Values.iri("https://pod.example/places/\uD840\uDC00"), context)
    }
    ResourceBoundary.requireWithinBoundary(resource, model)
  }
}
