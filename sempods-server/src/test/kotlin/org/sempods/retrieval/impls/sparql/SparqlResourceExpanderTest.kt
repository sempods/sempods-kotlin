package org.sempods.retrieval.impls.sparql

import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.query.SparqlQueryService
import org.sempods.query.spi.PodConnections
import org.sempods.retrieval.FindSandbox
import org.sempods.spec.PodRef
import java.net.URI
import kotlin.test.assertEquals

/** The expander over a store a deployment supplies through [PodConnections], with no pod registry. */
class SparqlResourceExpanderTest {

  private val pod = PodRef(uri = URI("https://pods.example/demo"), name = "demo", owner = "owner")
  private val public = iri("https://pods.example/demo/public")
  private val private = iri("https://pods.example/demo/private")
  private val harbour = iri("https://pods.example/demo/harbour")
  private val article = iri("https://schema.org/Article")

  private val repository = SailRepository(MemoryStore()).apply {
    init()
    connection.use { conn ->
      conn.add(harbour, RDF.TYPE, article, public)
      conn.add(harbour, RDFS.LABEL, literal("Harbour"), public)
      conn.add(harbour, RDFS.LABEL, literal("Harbour, internal"), private)
    }
  }

  private val connections = object : PodConnections {
    override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T {
      check(pod == this@SparqlResourceExpanderTest.pod) { "asked for ${pod.name}" }
      return repository.connection.use(block)
    }
  }

  private val expander = SparqlResourceExpander(SparqlQueryService(connections))

  @AfterEach
  fun shutDown() {
    repository.shutDown()
  }

  private fun sandbox(readable: Set<URI>) = FindSandbox(
    podUri = pod.uri,
    credentials = SempodsCredentials(pod = pod, restrictedContexts = readable),
    visibleContexts = readable,
  )

  @Test
  fun `a hit is expanded from the contexts the caller reads and nothing else`() {
    val expanded = expander.expand(listOf(harbour), sandbox(setOf(URI(public.stringValue()))))

    assertEquals(setOf(public), expanded.contexts())
    assertEquals(setOf(literal("Harbour")), expanded.filter(harbour, RDFS.LABEL, null).objects())
    assertEquals(setOf(article), expanded.filter(harbour, RDF.TYPE, null).objects())
  }

  @Test
  fun `the contexts holding a hit are those the caller reads`() {
    assertEquals(
      mapOf(harbour to setOf(public, private)),
      expander.holdingContexts(listOf(harbour), sandbox(setOf(URI(public.stringValue()), URI(private.stringValue())))),
    )
    assertEquals(
      mapOf(harbour to setOf(public)),
      expander.holdingContexts(listOf(harbour), sandbox(setOf(URI(public.stringValue())))),
    )
  }

  @Test
  fun `an empty scope reaches no store`() {
    assertEquals(0, expander.expand(listOf(harbour), sandbox(emptySet())).size)
  }
}
