package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.tripleTerm
import org.eclipse.rdf4j.rio.helpers.TripleTermUtil
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsSession
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** How a read reports a body that is not N-Quads, and that one that is comes back with every value as the pod sent it. */
class SempodsRdf4jDecodingTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val event get() = "$origin/alice/events/1"

  private fun read(body: ByteArray): SempodsResponse<Model> {
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("ETag", "\"v1\"").withBody(body))
    return SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)).resources().getModel(event)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "<urn:s> <urn:p> .",
      "<urn:s> <urn:p> \"secret\" <urn:g> <urn:extra> .",
      "<urn:s> <urn:p> \"secret .",
      "<not an iri> <urn:p> \"secret\" .",
    ],
  )
  fun `a body that is not N-Quads is a decoding failure with the answer's status and headers, quoting no body`(body: String) {
    val refused = assertThrows<SempodsDecodingException> { read("$body\n".toByteArray()) }

    assertEquals(200, refused.status)
    assertEquals("\"v1\"", refused.headers["ETag"])
    assertFalse("secret" in refused.message.orEmpty(), refused.message)
  }

  @Test
  fun `malformed UTF-8 is a decoding failure`() {
    val bytes = "<urn:s> <urn:p> \"caf".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\" .\n".toByteArray()

    assertThrows<SempodsDecodingException> { read(bytes) }
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  fun `a JVM flag does not decide what a read returns`() {
    val skipInvalidLines = "org.eclipse.rdf4j.rio.ntriples.fail_on_invalid_lines"
    val normalizeTags = "org.eclipse.rdf4j.rio.normalize_language_tags"
    System.setProperty(skipInvalidLines, "false")
    System.setProperty(normalizeTags, "true")
    try {
      // Read as `"o"^^<urn:t>` when invalid lines are skipped.
      assertThrows<SempodsDecodingException> { read("<urn:s> <urn:p> \"o\"^x <urn:t> .\n".toByteArray()) }
      server.reset()
      val literal = read("<urn:s> <urn:p> \"x\"@en-us .\n".toByteArray()).body!!.single().`object`
      assertEquals("en-us", assertIs<Literal>(literal).language.get())
    } finally {
      System.clearProperty(skipInvalidLines)
      System.clearProperty(normalizeTags)
    }
  }

  @Test
  fun `every value comes back as the pod sent it`() {
    // An IRI RDF4J itself would read back as a triple term.
    val encodedTriple = TripleTermUtil.toRDFEncodedValue<Value>(tripleTerm(iri("urn:s"), iri("urn:p"), iri("urn:o"))).stringValue()
    val model = assertNotNull(
      read(
        """
        <urn:s> <urn:p:lang> "Grüezi"@de-CH .
        <urn:s> <urn:p:lower> "x"@en-us .
        <urn:s> <urn:p:integer> "042"^^<http://www.w3.org/2001/XMLSchema#integer> .
        <urn:s> <urn:p:boolean> "1"^^<http://www.w3.org/2001/XMLSchema#boolean> .
        <urn:s> <urn:p:triple> <$encodedTriple> .
        """.trimIndent().toByteArray(),
      ).body,
    )

    fun value(predicate: String) = model.filter(null, iri(predicate), null).single().`object`

    assertEquals("de-CH", assertIs<Literal>(value("urn:p:lang")).language.get())
    assertEquals("en-us", assertIs<Literal>(value("urn:p:lower")).language.get())
    assertEquals("042", value("urn:p:integer").stringValue())
    assertEquals("1", value("urn:p:boolean").stringValue())
    assertEquals(encodedTriple, assertIs<IRI>(value("urn:p:triple")).stringValue())
    assertTrue(model.contexts().single() == null, "statements without a context stay without one")
  }
}
