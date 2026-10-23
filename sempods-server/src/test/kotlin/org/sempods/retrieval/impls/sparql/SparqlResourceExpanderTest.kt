package org.sempods.retrieval.impls.sparql

import io.mockk.every
import io.mockk.mockk
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.Sail
import org.eclipse.rdf4j.sail.inferencer.InferencerConnection
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.sempods.pods.PodRepository
import org.sempods.pods.PodRepositoryCache
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.query.SparqlQueryService
import org.sempods.retrieval.FindSandbox
import org.sempods.spec.PodRef
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [SparqlResourceExpander] against a real RDF4J store: what `FindService` relies on to verify the
 * hits of an adapter that is not self-scoped — every asserted `rdf:type` in scope, nothing from
 * outside it, nothing inferred.
 */
class SparqlResourceExpanderTest {

  private val vf = SimpleValueFactory.getInstance()
  private val readable = vf.createIRI("https://example.org/t/readable")
  private val unreadable = vf.createIRI("https://example.org/t/unreadable")
  private val hit = vf.createIRI("https://example.org/t/hit")
  private val event = vf.createIRI("https://schema.org/Event")
  private val thing = vf.createIRI("https://schema.org/Thing")
  private val person = vf.createIRI("https://schema.org/Person")

  private val store: Sail = MemoryStore()
  private val repository = SailRepository(store).apply { init() }

  private val podRepository = mockk<PodRepository> {
    every { withConnection<Model>(any()) } answers {
      repository.connection.use { firstArg<(RepositoryConnection) -> Model>()(it) }
    }
  }
  private val podRepositoryCache = mockk<PodRepositoryCache>().also { cache ->
    every { cache.get("t") } returns podRepository
  }
  private val expander = SparqlResourceExpander(SparqlQueryService(podRepositoryCache))

  @AfterEach
  fun shutDown() {
    repository.shutDown()
  }

  private fun sandbox(scope: Set<IRI>?, filter: Set<IRI>? = null) = FindSandbox(
    podUri = URI("https://example.org/t"),
    credentials = SempodsCredentials(pod = PodRef(uri = URI("https://example.org/t"), name = "t", owner = "o"), restrictedContexts = null),
    visibleContexts = scope?.map { URI(it.stringValue()) }?.toSet(),
    contextFilter = filter?.map { URI(it.stringValue()) }?.toSet(),
  )

  private fun typesOf(model: Model): Set<IRI> = model.filter(hit, RDF.TYPE, null).objects().filterIsInstance<IRI>().toSet()

  @Test
  fun `returns every asserted type in scope and nothing from outside it`() {
    repository.connection.use { conn ->
      conn.add(hit, RDF.TYPE, event, readable)
      conn.add(hit, RDF.TYPE, thing, readable)
      conn.add(hit, RDF.TYPE, person, unreadable)
    }

    val expansion = expander.expand(listOf(hit), sandbox(scope = setOf(readable)))

    assertEquals(setOf(event, thing), typesOf(expansion))
    assertTrue(expansion.contexts().all { it == readable }, "every statement comes from the readable context")
  }

  @Test
  fun `a hit that exists only outside the scope is not expanded`() {
    repository.connection.use { conn -> conn.add(hit, RDF.TYPE, person, unreadable) }

    assertTrue(expander.expand(listOf(hit), sandbox(scope = setOf(readable))).isEmpty())
    assertTrue(expander.expand(listOf(hit), sandbox(scope = setOf(readable, unreadable), filter = setOf(readable))).isEmpty())
  }

  @Test
  fun `does not return inferred statements`() {
    repository.connection.use { conn -> conn.add(hit, RDF.TYPE, event, readable) }
    (store.connection as InferencerConnection).use { conn ->
      conn.begin()
      conn.addInferredStatement(hit, RDF.TYPE, thing, readable)
      conn.commit()
    }

    assertEquals(setOf(event), typesOf(expander.expand(listOf(hit), sandbox(scope = setOf(readable)))))
  }

  @Test
  fun `an empty effective scope returns nothing`() {
    repository.connection.use { conn -> conn.add(hit, RDF.TYPE, event, readable) }

    assertTrue(expander.expand(listOf(hit), sandbox(scope = setOf(readable), filter = emptySet())).isEmpty())
  }

  @Test
  fun `no scope restriction expands across every context`() {
    repository.connection.use { conn ->
      conn.add(hit, RDF.TYPE, event, readable)
      conn.add(hit, RDF.TYPE, person, unreadable)
    }

    assertEquals(setOf(event, person), typesOf(expander.expand(listOf(hit), sandbox(scope = null))))
  }

  @Test
  fun `an IRI that cannot be put into the query is left unexpanded`() {
    repository.connection.use { conn -> conn.add(hit, RDF.TYPE, event, readable) }
    val malformed = vf.createIRI("https://example.org/t/x> } <y")

    val expansion = expander.expand(listOf(malformed, hit), sandbox(scope = setOf(readable)))

    assertEquals(setOf(event), typesOf(expansion))
    assertTrue(expander.expand(listOf(malformed), sandbox(scope = setOf(readable))).isEmpty())
  }
}
