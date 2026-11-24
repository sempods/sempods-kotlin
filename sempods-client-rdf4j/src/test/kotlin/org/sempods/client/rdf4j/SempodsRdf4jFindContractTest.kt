package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.util.Values.iri
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsFindQuery
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** What the find group asks the pod for, and how its answer becomes a model. */
class SempodsRdf4jFindContractTest : MockPodTest() {

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

  private val find get() = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)).find()

  private val article = "https://pod.example/articles/42"

  private val a = "https://pod.example/_system/contexts/A"

  private val b = "https://pod.example/_system/contexts/B"

  private val findResult = iri("https://schema.sempods.org/findResult")

  private val text = iri("https://schema.org/text")

  private fun answer(body: String) {
    server.`when`(request()).respond(response().withStatusCode(200).withBody(body))
  }

  @Test
  fun `a find asks for N-Quads with the query's body, and a flat answer carries no context`() {
    answer("<$article> <${findResult.stringValue()}> _:m .\n_:m <${text.stringValue()}> \"Harbour lantern\" .\n")

    val hits = find.model(SempodsFindQuery.text("harbour").withSelection(SempodsContextSelection.of(a)))

    val request = sent.single()
    assertEquals("POST", request.method)
    assertEquals("/alice/_system/find", request.url.encodedPath)
    assertEquals("application/n-quads", request.headers["Accept"])
    assertEquals("""{"text":"harbour","contexts":["$a"]}""", request.body?.decodeToString())
    val model = assertNotNull(hits.body)
    assertEquals(2, model.size)
    assertEquals(setOf(null), model.contexts())
  }

  @Test
  fun `a grouped answer keeps every context, and a link and its details split across two stay one node`() {
    // sempods-spec examples/find/responses.yaml, "split".
    answer("<$article> <${findResult.stringValue()}> _:m <$a> .\n_:m <${text.stringValue()}> \"Harbour lantern\" <$b> .\n")

    val model = assertNotNull(find.model(SempodsFindQuery.text("harbour").withIncludeContexts(true)).body)

    assertEquals("""{"text":"harbour","include_contexts":true}""", sent.single().body?.decodeToString())
    val link = model.filter(iri(article), findResult, null).single()
    assertEquals(iri(a), link.context)
    val observation = assertIs<BNode>(link.`object`)
    val detail = model.filter(observation, text, null).single()
    assertEquals(iri(b), detail.context)
  }

  @Test
  fun `two answers never share a blank node`() {
    answer("<$article> <${findResult.stringValue()}> _:m .\n")

    val first = assertNotNull(find.model(SempodsFindQuery.text("harbour")).body).objects().single()
    val second = assertNotNull(find.model(SempodsFindQuery.text("harbour")).body).objects().single()

    assert(first != second) { "both answers resolved _:m to $first" }
  }

  @Test
  fun `an answer that is no N-Quads is a decoding failure`() {
    answer("[]")

    assertThrows<SempodsDecodingException> { find.model(SempodsFindQuery.text("harbour")) }
  }
}
