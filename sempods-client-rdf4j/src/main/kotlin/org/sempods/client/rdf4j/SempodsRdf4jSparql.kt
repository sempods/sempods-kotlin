package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.sempods.client.SempodsBodyReader
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsPodSparql
import org.sempods.client.SempodsResponse
import java.io.IOException

/**
 * [SempodsPodSparql] with RDF4J values: a CONSTRUCT or DESCRIBE graph as a [Model], and a SELECT result as
 * [org.eclipse.rdf4j.query.BindingSet]s.
 *
 * ```java
 * Model graph = rdf.sparql().graphModel("CONSTRUCT WHERE { ?s ?p ?o }").getBody();
 * for (BindingSet row : rdf.sparql().select("SELECT ?s ?name WHERE { ?s schema:name ?name }").getBody().getBindingSets()) { ... }
 * ```
 *
 * **A graph asks for N-Quads** (SPS-SPARQL-016). A CONSTRUCT or DESCRIBE result is a set of triples, so
 * its statements carry no context, whatever `GRAPH` clause matched them.
 *
 * **A SELECT result is the core's strict one** ([SempodsPodSparql.select]), converted term by term. A term
 * RDF4J cannot hold — a `uri` that is no absolute IRI, a datatype `rdf:langString` without a language —
 * is a [org.sempods.client.SempodsDecodingException] with the answer's status and headers.
 *
 * An ASK query needs nothing from here: [SempodsPodSparql.ask] answers the `boolean` RDF4J would.
 * The selection, answers and the resend are [SempodsPodSparql]'.
 */
class SempodsRdf4jSparql private constructor(
  private val core: SempodsPodSparql,
) {

  /** A CONSTRUCT or DESCRIBE query's graph. */
  @JvmOverloads
  @Throws(IOException::class)
  fun graphModel(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<Model> {
    val answer = core.graphBytes(query, SempodsGraphFormat.N_QUADS, selection)
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /**
   * A CONSTRUCT or DESCRIBE query's graph, handed to [handler] as it arrives, with no limit on its size;
   * the body is the number of statements.
   *
   * What [handler] throws, and an `IOException` of the connection, reach the caller as they are. A body
   * that does not parse is a [org.sempods.client.SempodsDecodingException], after the statements
   * before it were handed on.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun graphStream(
    query: String,
    handler: RDFHandler,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<Long> {
    val reader = SempodsBodyReader { body -> readStatements(body, RDFFormat.NQUADS, baseUri = null, handler, context = null) }
    return core.graphStream(query, SempodsGraphFormat.N_QUADS, reader, selection).map { it.countOrThrow() }
  }

  /** A SELECT query's result, one [org.eclipse.rdf4j.query.BindingSet] per solution. */
  @JvmOverloads
  @Throws(IOException::class)
  fun select(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<SempodsRdf4jSelectResults> = core.select(query, selection).map { selectResultsOf(it) }

  internal companion object {

    @JvmSynthetic
    internal fun of(core: SempodsPodSparql): SempodsRdf4jSparql = SempodsRdf4jSparql(core)
  }
}
