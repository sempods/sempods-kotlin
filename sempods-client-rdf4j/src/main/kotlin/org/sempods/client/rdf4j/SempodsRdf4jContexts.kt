package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.RDFHandler
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.sempods.client.core.SempodsBodyReader
import org.sempods.client.core.SempodsContextCreate
import org.sempods.client.core.SempodsGraphFormat
import org.sempods.client.core.SempodsPodContexts
import org.sempods.client.core.SempodsResponse
import java.io.IOException

/**
 * [SempodsPodContexts] with a [Model] for the registry's answers — the catalogue, one context's
 * description, the description a creation answers with — and for everything in one context.
 *
 * ```java
 * Model catalogue = rdf.contexts().listModel().getBody();
 * Model tasks = rdf.contexts().create(tasksIri, SempodsContextCreate.fields().withLabel("Tasks")).getBody();
 * ```
 *
 * **Every read asks for N-Quads**, which a registry serves as a resource route does (SPS-CTX-031), so a
 * statement keeps the context the pod put it in. A body that does not parse as N-Quads is a
 * [org.sempods.client.core.SempodsDecodingException] with the answer's status and headers.
 *
 * **An export is read while it arrives** ([SempodsPodContexts.export]), into a handler or into a model,
 * and every statement carries the exported context: the query answers triples. What the handler throws,
 * and an `IOException` of the connection, reach the caller as they are; a body that does not parse is a
 * decoding failure, after the statements before it were handed on.
 *
 * Addresses, answers, entity tags and the resend are [SempodsPodContexts]'. Removing a context answers
 * no graph, so it stays there.
 */
class SempodsRdf4jContexts internal constructor(
  private val core: SempodsPodContexts,
) {

  /** The catalogue of the contexts the session sees, unchanged from [ifNoneMatch] with `304`. */
  @JvmOverloads
  @Throws(IOException::class)
  fun listModel(ifNoneMatch: String? = null): SempodsResponse<Model> {
    val answer = core.listBytes(SempodsGraphFormat.N_QUADS, ifNoneMatch)
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /** What the registry holds for [contextUri] (SPS-CTX-024). */
  @JvmOverloads
  @Throws(IOException::class)
  fun getModel(contextUri: String, ifNoneMatch: String? = null): SempodsResponse<Model> {
    val answer = core.getBytes(contextUri, SempodsGraphFormat.N_QUADS, ifNoneMatch)
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /** Creates the context [contextUri], or finds it, and reads the description the pod answers with. */
  @JvmOverloads
  @Throws(IOException::class)
  fun create(
    contextUri: String,
    body: SempodsContextCreate = SempodsContextCreate.fields(),
  ): SempodsResponse<Model> {
    val answer = core.create(contextUri, body, SempodsGraphFormat.N_QUADS)
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /** Everything the session may read in [contextUri], handed to [handler] as it arrives; the body is the number of statements. */
  @Throws(IOException::class)
  fun export(contextUri: String, handler: RDFHandler): SempodsResponse<Long> {
    val context = values.createIRI(contextUri)
    val reader = SempodsBodyReader { body -> readStatements(body, RDFFormat.NQUADS, contextUri, handler, context) }
    return core.export(contextUri, reader).map { it.countOrThrow() }
  }

  /** The same export as a model: the whole context in memory, with no limit on its size. */
  @Throws(IOException::class)
  fun exportModel(contextUri: String): SempodsResponse<Model> {
    val model = LinkedHashModel()
    return export(contextUri, StatementCollector(model)).map { model }
  }

  private companion object {

    val values: SimpleValueFactory = SimpleValueFactory.getInstance()
  }
}
