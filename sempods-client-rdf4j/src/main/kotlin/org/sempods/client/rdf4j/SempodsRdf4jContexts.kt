package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.sempods.client.core.SempodsContextCreate
import org.sempods.client.core.SempodsGraphFormat
import org.sempods.client.core.SempodsPodContexts
import org.sempods.client.core.SempodsResponse
import java.io.IOException

/**
 * [SempodsPodContexts] with a [Model] for the registry's answers: the catalogue, one context's
 * description, and the description a creation answers with.
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
 * Addresses, answers, entity tags and the resend are [SempodsPodContexts]'. Removing a context answers
 * no graph, and an export is a stream, so both stay there.
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
}
