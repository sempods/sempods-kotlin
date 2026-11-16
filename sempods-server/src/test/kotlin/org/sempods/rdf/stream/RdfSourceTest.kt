package org.sempods.rdf.stream

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RdfSourceTest {

  private val model = LinkedHashModel().apply {
    add(Values.iri("urn:s"), Values.iri("urn:p"), Values.literal("o"), Values.iri("urn:g"))
    add(Values.iri("urn:s"), Values.iri("urn:p"), Values.iri("urn:o"))
  }

  @Test
  fun `a model source writes every statement with its context`() {
    val out = ByteArrayOutputStream()

    RdfSource.of(model).write(RDFFormat.NQUADS, out)

    assertEquals(model, Rio.parse(out.toByteArray().inputStream(), RDFFormat.NQUADS))
  }

  @Test
  fun `writing leaves the stream open for whoever opened it`() {
    val out = object : ByteArrayOutputStream() {
      var closed = false
      override fun close() {
        closed = true
      }
    }

    RdfSource.of(model).write(RDFFormat.JSONLD, out)

    assertFalse(out.closed)
  }

  @Test
  fun `the streaming entity writes the same document`() {
    val direct = ByteArrayOutputStream().also { RdfSource.of(model).write(RDFFormat.NQUADS, it) }
    val streamed = ByteArrayOutputStream().also { RdfSource.of(model).toStreamingOutput(RDFFormat.NQUADS).write(it) }

    assertEquals(direct.toString(Charsets.UTF_8), streamed.toString(Charsets.UTF_8))
  }
}
