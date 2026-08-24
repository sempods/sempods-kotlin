package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.ontologies.Ontologies
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertTrue

/**
 * On reload the MemoryStore is reconstructed from the per-context backup collection — the pod's
 * only durable persistence, with no legacy fallback left.
 *
 * The tests assert against the reconstructed store directly via [PodRepository.withConnection], the
 * most direct evidence that recovery repopulated the store.
 */
class RecoverySwitchIntegrationTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  private val context = Values.iri("https://pods.test/recovery/contexts/main")
  private val other = Values.iri("https://pods.test/recovery/contexts/other")

  private fun readResourceFromStore(repo: PodRepository, resourceIri: IRI): Model =
    repo.withConnection { conn ->
      val model = LinkedHashModel()
      conn.getStatements(resourceIri, null, null, false).use { result -> result.forEach(model::add) }
      model
    }

  @Test
  fun `recovery rebuilds the store from the backup collection`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/rec-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Recover me"), context)
    }
    assertTrue(repo.putResource(resourceUri, model))

    podRepositoryCache.invalidate(pod.name)
    val recovered = readResourceFromStore(podRepositoryCache.get(pod.name)!!, resourceIri)
    assertTrue(Models.isomorphic(model, recovered), "store must be rebuilt from the backup collection, got: $recovered")
  }

  @Test
  fun `recovery restores a resource spread across contexts, and only its surviving contexts`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/rec-multi-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()

    val repo = podRepositoryCache.get(pod.name)!!
    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("In main"), context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("In other"), other)
    }
    assertTrue(repo.putResource(resourceUri, model))

    // Drop one context: its backup row must go with it, the sibling row must survive.
    assertTrue(repo.removeFromContext(resourceUri, URI(other.stringValue())))

    podRepositoryCache.invalidate(pod.name)
    val recovered = readResourceFromStore(podRepositoryCache.get(pod.name)!!, resourceIri)

    val expected = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("In main"), context)
    }
    assertTrue(
      Models.isomorphic(expected, recovered),
      "the dropped context's backup row must not be recovered, got: $recovered",
    )
  }
}
