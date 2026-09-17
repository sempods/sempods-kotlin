package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsContextSelection
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the SPARQL group asks the pod for, and how a graph and a SELECT result become RDF4J values. */
class SempodsRdf4jSparqlContractTest : MockPodTest() {

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = recordingClient(sent)

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  @BeforeEach
  fun forgetRequests() {
    sent.clear()
  }

  private val sparql get() = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)).sparql()

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private fun answer(body: String) {
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("X-Trace", "t-1").withBody(body))
  }

  @Test
  fun `a graph asks for N-Quads in the selected contexts, and its statements carry no context`() {
    answer("<urn:s> <urn:p> \"o\" .\n<urn:s> <urn:q> _:b1 .\n")

    val graph = sparql.graphModel("CONSTRUCT WHERE { ?s ?p ?o }", SempodsContextSelection.of(tasks))

    val query = sent.single()
    assertEquals("/alice/_system/sparql/query", query.url.encodedPath)
    assertEquals("application/n-quads", query.headers["Accept"])
    assertEquals(listOf(tasks), query.url.queryParameterValues("default-graph-uri"))
    val model = assertNotNull(graph.body)
    assertEquals(2, model.size)
    assertEquals(setOf(null), model.contexts())
  }

  @Test
  fun `a SELECT result keeps its variables and every term, and leaves an unbound variable without a binding`() {
    answer(
      """
      {"head": {"vars": ["s", "name", "count", "note", "anon"]},
       "results": {"bindings": [
         {"s": {"type": "uri", "value": "https://pods.example/alice/people/bob"},
          "name": {"type": "literal", "value": "Grüezi", "xml:lang": "de-CH"},
          "count": {"type": "literal", "value": "042", "datatype": "${XSD.INTEGER}"},
          "note": {"type": "literal", "value": "plain"},
          "anon": {"type": "bnode", "value": "b1"}},
         {"s": {"type": "uri", "value": "https://pods.example/alice/people/carol"}}
       ]}}
      """.trimIndent(),
    )

    val results = assertNotNull(sparql.select("SELECT * WHERE { ?s ?p ?o }").body)

    assertEquals(listOf("s", "name", "count", "note", "anon"), results.variables)
    val (bob, carol) = results.bindingSets
    assertEquals(iri("https://pods.example/alice/people/bob"), bob.getValue("s"))
    assertEquals("de-CH", assertIs<Literal>(bob.getValue("name")).language.get())
    assertEquals(literal("042", XSD.INTEGER), bob.getValue("count"))
    assertEquals(literal("plain"), bob.getValue("note"))
    assertEquals("b1", assertIs<BNode>(bob.getValue("anon")).id)
    assertEquals(results.variables.toSet(), carol.bindingNames)
    assertFalse(carol.hasBinding("name"))
    assertNull(carol.getValue("name"))
  }

  @Test
  fun `a term RDF4J cannot hold is a decoding failure with the answer's status and headers`() {
    answer("""{"head": {"vars": ["s"]}, "results": {"bindings": [{"s": {"type": "uri", "value": "not-an-iri"}}]}}""")

    val refused = assertThrows<SempodsDecodingException> { sparql.select("SELECT ?s WHERE { ?s ?p ?o }") }

    assertEquals(200, refused.status)
    assertEquals("t-1", refused.headers["X-Trace"])
  }
}
