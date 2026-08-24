package org.sempods.pods

import com.google.inject.Inject
import org.sempods.SempodsIntegrationTest
import org.sempods.ontologies.Ontologies
import org.sempods.rdf.toIri
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Resource reads (`getResource`, `existsResource`, `findReferencingResources`, the ETag validator)
 * are served from the in-memory store, not MongoDB.
 */
class StoreReadIntegrationTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podRepositoryCache: PodRepositoryCache

  private val context = Values.iri("https://pods.test/storeread/contexts/main")
  private val other = Values.iri("https://pods.test/storeread/contexts/other")
  private val knows = Values.iri("https://schema.org/knows")

  @Test
  fun `getResource and the validator are served from the store`() {
    val pod = sempodsTestFactory.newPod()
    val resourceUri = URI("https://example.org/sr-${System.nanoTime()}")
    val resourceIri = resourceUri.toIri()
    val repo = podRepositoryCache.get(pod.name)!!

    val model = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Read me"), context)
    }
    assertTrue(repo.putResource(resourceUri, model))

    val validatorBefore = repo.fetchResourceValidator(resourceUri)
    assertNotNull(validatorBefore)

    val read = repo.getResource(resourceUri)
    assertNotNull(read, "getResource must read from the store")
    assertTrue(Models.isomorphic(model, read))

    // The validator is stable for unchanged content and changes after a content change.
    assertEquals(validatorBefore, repo.fetchResourceValidator(resourceUri), "validator must be stable")
    val updated = LinkedHashModel().apply {
      add(resourceIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(resourceIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("Changed"), context)
    }
    assertTrue(repo.putResource(resourceUri, updated))
    assertNotEquals(validatorBefore, repo.fetchResourceValidator(resourceUri), "validator must change on content change")
  }

  @Test
  fun `getResource by context, existsResource and findReferencingResources query the store`() {
    val pod = sempodsTestFactory.newPod()
    val repo = podRepositoryCache.get(pod.name)!!
    val a = URI("https://example.org/a-${System.nanoTime()}")
    val b = URI("https://example.org/b-${System.nanoTime()}")
    val aIri = a.toIri()
    val bIri = b.toIri()

    // A has a name in `context` and a non-type edge to B; plus a statement in `other`.
    val aModel = LinkedHashModel().apply {
      add(aIri, RDF.TYPE, Ontologies.SCHEMA_ORG.Types.Event, context)
      add(aIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("A"), context)
      add(aIri, knows, bIri, context)
      add(aIri, Ontologies.SCHEMA_ORG.Properties.name, Values.literal("A-other"), other)
    }
    assertTrue(repo.putResource(a, aModel))

    // getResource(uri, context) returns only that context's statements.
    val inContext = repo.getResource(a, URI(context.stringValue()))!!
    assertTrue(inContext.all { it.context == context }, "must be scoped to the requested context")
    assertTrue(inContext.contains(aIri, knows, bIri, context))
    val inOther = repo.getResource(a, URI(other.stringValue()))!!
    assertEquals(1, inOther.size)

    // existsResource: base, type filter, context filter.
    assertTrue(repo.existsResource(a))
    assertTrue(repo.existsResource(a, types = listOf(URI(Ontologies.SCHEMA_ORG.Types.Event.stringValue()))))
    assertFalse(repo.existsResource(a, types = listOf(URI("https://schema.org/Person"))))
    assertTrue(repo.existsResource(a, contexts = listOf(URI(context.stringValue()))))
    assertFalse(repo.existsResource(a, contexts = listOf(URI("https://pods.test/storeread/contexts/absent"))))
    assertFalse(repo.existsResource(URI("https://example.org/missing-${System.nanoTime()}")))

    // findReferencingResources: A references B via a non-type edge in `context`.
    assertEquals(setOf(a), repo.findReferencingResources(URI(context.stringValue()), b))
    // No references in `other`.
    assertTrue(repo.findReferencingResources(URI(other.stringValue()), b).isEmpty())

    // rdf:type edges are excluded: E typed as B does not count as referencing B.
    val e = URI("https://example.org/e-${System.nanoTime()}")
    val eModel = LinkedHashModel().apply { add(e.toIri(), RDF.TYPE, bIri, context) }
    assertTrue(repo.putResource(e, eModel))
    assertEquals(setOf(a), repo.findReferencingResources(URI(context.stringValue()), b), "rdf:type edges must be excluded")

    assertNull(repo.fetchResourceValidator(URI("https://example.org/never-${System.nanoTime()}")))
  }

  private fun assertNotEquals(unexpected: Any?, actual: Any?, message: String) =
    assertFalse(unexpected == actual, message)
}
