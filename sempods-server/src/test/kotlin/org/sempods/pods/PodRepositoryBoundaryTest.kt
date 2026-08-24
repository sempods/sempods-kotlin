package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.ontologies.Ontologies
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Boundary invariant on the persistence backstop ([InMemoryPodRepository.putResource]): a resource
 * may only carry statements about itself, and blank nodes are forbidden. The HTTP layer already
 * rejects these with 400; this covers the in-process write path, which never sees that check.
 */
class PodRepositoryBoundaryTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  private val context = Values.iri("https://pods.test/boundary/contexts/main")
  private val otherContext = Values.iri("https://pods.test/boundary/contexts/other")

  @Test
  fun `putResource rejects a model with foreign named subjects`() {
    val repo = podRepositoryCache.get(sempodsTestFactory.newPod().name)!!

    val eventUri = URI("https://pods.test/events/boundary-1-${System.nanoTime()}")
    val model = LinkedHashModel().apply {
      add(Values.iri(eventUri.toString()), RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(Values.iri("https://tickets.example.org/offer-1"), Ontologies.SCHEMA_ORG.Properties.url, Values.literal("x"), context)
    }
    assertThrows<IllegalArgumentException> { repo.putResource(eventUri, model) }
  }

  @Test
  fun `putResource rejects a blank-node object`() {
    val repo = podRepositoryCache.get(sempodsTestFactory.newPod().name)!!

    val eventUri = URI("https://pods.test/events/boundary-2-${System.nanoTime()}")
    val eventIri = Values.iri(eventUri.toString())
    val model = LinkedHashModel().apply {
      add(eventIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(eventIri, Ontologies.SCHEMA_ORG.Properties.location, Values.bnode(), context)
    }
    assertThrows<IllegalArgumentException> { repo.putResource(eventUri, model) }
  }

  @Test
  fun `putResource rejects a blank-node subject`() {
    val repo = podRepositoryCache.get(sempodsTestFactory.newPod().name)!!

    val eventUri = URI("https://pods.test/events/boundary-3-${System.nanoTime()}")
    val model = LinkedHashModel().apply {
      add(Values.iri(eventUri.toString()), RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(Values.bnode(), Ontologies.SCHEMA_ORG.Properties.name, Values.literal("nested"), context)
    }
    assertThrows<IllegalArgumentException> { repo.putResource(eventUri, model) }
  }

  @Test
  fun `putResource accepts and round-trips a clean IRI-valued resource across contexts`() {
    val pod = sempodsTestFactory.newPod()
    val repo = podRepositoryCache.get(pod.name)!!

    val eventUri = URI("https://pods.test/${pod.name}/events/boundary-4-${System.nanoTime()}")
    val eventIri = Values.iri(eventUri.toString())
    val model = LinkedHashModel().apply {
      add(eventIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      // IRI object instead of a blank node — a linked, addressable resource.
      add(eventIri, Ontologies.SCHEMA_ORG.Properties.location, Values.iri("https://pods.test/${pod.name}/places/stadtpark"), context)
      add(eventIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Other"), otherContext)
    }
    assertTrue(repo.putResource(eventUri, model))

    val stored = assertNotNull(repo.getResource(eventUri))
    assertEquals(3, stored.size)
    assertEquals(1, stored.getStatements(eventIri, Ontologies.SCHEMA_ORG.Properties.name, null, otherContext).count())
  }
}
