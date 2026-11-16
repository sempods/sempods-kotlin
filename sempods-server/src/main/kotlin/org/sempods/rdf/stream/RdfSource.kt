package org.sempods.rdf.stream

import jakarta.ws.rs.core.StreamingOutput
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.eclipse.rdf4j.rio.Rio
import java.io.OutputStream

/**
 * A producer of RDF statements that pushes them into a [RDFHandler] — the shape RDF4J streams in.
 *
 * The source hands over statements only: it calls [RDFHandler.handleStatement] and nothing else.
 * Starting and ending the document belongs to the sink ([write], [toStreamingOutput]), so a source
 * can be serialized in any format and composed with others.
 *
 * Push rather than an `Iterable`, so a source owns what it reads from. A source over a pod
 * repository opens its connection inside [writeTo] and closes it when the last statement is out;
 * the caller never holds a live result. Put into a [StreamingOutput], that work runs while the
 * response body is written.
 *
 * A source carries no namespaces: what a client receives depends on the statements alone, not on
 * the prefixes a query or model happened to carry.
 */
fun interface RdfSource {

  fun writeTo(handler: RDFHandler)

  companion object {
    /** A source over statements already in memory — a `Model` or any other collection. */
    fun of(statements: Iterable<Statement>): RdfSource =
      RdfSource { handler -> statements.forEach(handler::handleStatement) }
  }
}

/**
 * Serialize this source as one [format] document onto [out]. [out] stays open: whoever opened it
 * closes it — for a response body, the container.
 */
fun RdfSource.write(format: RDFFormat, out: OutputStream) {
  val writer = Rio.createWriter(format, out)
  writer.startRDF()
  writeTo(writer)
  writer.endRDF()
}

/**
 * A response entity that runs this source while the body is written.
 *
 * The status line and headers are sent before the first statement. A failure in the source after
 * that cannot become an error status: the client receives a truncated body.
 */
fun RdfSource.toStreamingOutput(format: RDFFormat): StreamingOutput =
  StreamingOutput { out -> write(format, out) }
