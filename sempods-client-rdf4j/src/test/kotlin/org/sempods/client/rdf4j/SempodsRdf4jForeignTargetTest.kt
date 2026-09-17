package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsForeignTarget
import org.sempods.client.core.SempodsRequestAuth
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A URI outside any pod, read as a model or into a handler, and the remote JSON-LD contexts it names. */
class SempodsRdf4jForeignTargetTest : MockPodTest() {

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

  private val foreign get() = SempodsRdf4jForeignTarget(SempodsForeignTarget(client).followingRedirects(2))

  private val foafName = "http://xmlns.com/foaf/0.1/name"

  private fun serve(path: String, status: Int, contentType: String?, body: String = "") {
    val response = response().withStatusCode(status)
    contentType?.let { response.withHeader("Content-Type", it) }
    if (body.isNotEmpty()) response.withBody(body)
    server.`when`(request().withPath(path)).respond(response)
  }

  private fun sentTo(path: String) = sent.filter { it.url.encodedPath == path }

  @Test
  fun `Accept follows the formats in order, and relative IRIs resolve against the URL that answered`() {
    server.`when`(request().withPath("/old")).respond(response().withStatusCode(302).withHeader("Location", "/profile"))
    serve("/profile", 200, "text/turtle; charset=utf-8", "<#me> <$foafName> \"Bob\" .")

    val read = foreign.getModel("$origin/old", listOf(RDFFormat.TURTLE, RDFFormat.JSONLD, RDFFormat.NQUADS))

    assertEquals("text/turtle, application/ld+json;q=0.999, application/n-quads;q=0.998", sent.first().headers["Accept"])
    assertEquals("$origin/profile", read.url)
    assertTrue(assertNotNull(read.body).contains(iri("$origin/profile#me"), iri(foafName), literal("Bob")))
  }

  @Test
  fun `every preference after the first is strictly lower, past the tenth, and more than 1000 formats are refused`() {
    serve("/many", 200, "text/turtle", "<#me> <$foafName> \"Bob\" .")

    foreign.getModel("$origin/many", List(12) { RDFFormat.TURTLE })

    val preferences = assertNotNull(sent.single().headers["Accept"]).split(", ")
      .map { it.substringAfter(";q=", missingDelimiterValue = "1").toDouble() }
    assertEquals(12, preferences.size)
    assertTrue(preferences.zipWithNext().all { (before, after) -> after < before }, "$preferences")

    sent.clear()
    assertThrows<IllegalArgumentException> { foreign.getModel("$origin/many", List(1001) { RDFFormat.TURTLE }) }
    assertTrue(sent.isEmpty())
  }

  @Test
  fun `an answer in a format not asked for, or without a type, is a decoding failure`() {
    serve("/quads", 200, "application/n-quads", "<urn:s> <urn:p> \"o\" .\n")
    serve("/untyped", 200, null, "<urn:s> <urn:p> \"o\" .\n")

    assertThrows<SempodsDecodingException> { foreign.getModel("$origin/quads", listOf(RDFFormat.TURTLE)) }
    assertThrows<SempodsDecodingException> { foreign.getModel("$origin/untyped", listOf(RDFFormat.TURTLE)) }
  }

  @Test
  fun `an answer outside 2xx has no model, and nothing is parsed`() {
    serve("/gone", 404, "text/plain", "not here")

    val gone = foreign.getModel("$origin/gone", listOf(RDFFormat.TURTLE))

    assertEquals(404, gone.status)
    assertNull(gone.body)
  }

  @Test
  fun `a format without a parser on the classpath is refused before anything is sent`() {
    assertThrows<IllegalArgumentException> { foreign.getModel("$origin/trix", listOf(RDFFormat.TURTLE, RDFFormat.TRIX)) }
    assertThrows<IllegalArgumentException> { foreign.getStream("$origin/trix", RDFFormat.TRIX, StatementCollector()) }

    assertTrue(sent.isEmpty())
  }

  @Test
  fun `a stream hands every statement to the handler, and carries no credential it was not given`() {
    serve("/profile", 200, "text/turtle", "<#me> <$foafName> \"Bob\" . <#me> <$foafName> \"Robert\" .")
    val handled = LinkedHashModel()

    val count = foreign.getStream("$origin/profile", RDFFormat.TURTLE, StatementCollector(handled))

    assertEquals(2L, count.body)
    assertEquals(setOf(iri("$origin/profile#me")), handled.subjects())
    assertNull(sent.single().headers["Authorization"])
  }

  @Test
  fun `a remote context is loaded through the same client, without the document's credential`() {
    serve("/doc", 200, "application/ld+json", """{"@context": "$origin/context.jsonld", "@id": "$origin/doc#me", "name": "Bob"}""")
    serve("/context.jsonld", 200, "application/ld+json", """{"@context": {"name": "$foafName"}}""")

    val read = foreign.getModel("$origin/doc", listOf(RDFFormat.JSONLD), SempodsRequestAuth.bearer("secret"))

    assertTrue(assertNotNull(read.body).contains(iri("$origin/doc#me"), iri(foafName), literal("Bob")))
    assertEquals("Bearer secret", sentTo("/doc").single().headers["Authorization"])
    val context = sentTo("/context.jsonld").single()
    assertNull(context.headers["Authorization"])
    assertEquals("application/ld+json, application/json;q=0.9", context.headers["Accept"])
  }

  @Test
  fun `a context that is missing or not JSON makes the document unreadable`() {
    serve("/missing", 200, "application/ld+json", """{"@context": "$origin/missing.jsonld", "@id": "urn:s", "name": "Bob"}""")
    serve("/missing.jsonld", 404, null)
    serve("/text", 200, "application/ld+json", """{"@context": "$origin/text.jsonld", "@id": "urn:s", "name": "Bob"}""")
    serve("/text.jsonld", 200, "text/plain", """{"@context": {"name": "$foafName"}}""")

    assertThrows<SempodsDecodingException> { foreign.getModel("$origin/missing", listOf(RDFFormat.JSONLD)) }
    assertThrows<SempodsDecodingException> { foreign.getModel("$origin/text", listOf(RDFFormat.JSONLD)) }
  }

  @Test
  fun `more than ten remote contexts make the document unreadable, and no more than ten are loaded`() {
    val contexts = (1..11).map { "$origin/context-$it.jsonld" }
    contexts.forEachIndexed { index, url -> serve(url.removePrefix(origin), 200, "application/ld+json", """{"@context": {"p$index": "urn:p:$index"}}""") }
    serve("/many", 200, "application/ld+json", """{"@context": [${contexts.joinToString { "\"$it\"" }}], "@id": "urn:s", "p0": "x"}""")

    assertThrows<SempodsDecodingException> { foreign.getModel("$origin/many", listOf(RDFFormat.JSONLD)) }

    assertEquals(10, sent.count { it.url.encodedPath.startsWith("/context-") })
  }

  @Test
  fun `an IOException while loading a context reaches the caller as it is`() {
    serve("/unreachable", 200, "application/ld+json", """{"@context": "http://127.0.0.1:1/context.jsonld", "@id": "urn:s", "name": "Bob"}""")

    val failure = assertThrows<IOException> { foreign.getModel("$origin/unreachable", listOf(RDFFormat.JSONLD)) }

    assertFalse(failure is SempodsDecodingException, "$failure")
  }

  @Test
  fun `a stream loads no remote context`() {
    serve("/doc", 200, "application/ld+json", """{"@context": "$origin/context.jsonld", "@id": "$origin/doc#me", "name": "Bob"}""")
    serve("/context.jsonld", 200, "application/ld+json", """{"@context": {"name": "$foafName"}}""")

    assertThrows<SempodsDecodingException> { foreign.getStream("$origin/doc", RDFFormat.JSONLD, StatementCollector()) }

    assertTrue(sentTo("/context.jsonld").isEmpty())
  }
}
