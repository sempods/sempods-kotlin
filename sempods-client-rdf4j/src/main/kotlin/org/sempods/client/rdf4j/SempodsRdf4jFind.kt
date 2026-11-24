package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.sempods.client.SempodsFindQuery
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsPodFind
import org.sempods.client.SempodsResponse
import java.io.IOException

/**
 * [SempodsPodFind] with an RDF4J [Model] for the answer.
 *
 * ```java
 * Model hits = rdf.find().model(SempodsFindQuery.text("harbour")).getBody();
 * Model grouped = rdf.find().model(SempodsFindQuery.text("harbour").withIncludeContexts(true)).getBody();
 * ```
 *
 * **The answer is asked for as N-Quads** (SPS-FIND-020). The query's
 * [SempodsFindQuery.includeContexts] decides what the model's statements carry: without it, no context
 * (SPS-FIND-026); with it, the context the pod placed each statement in (SPS-FIND-027).
 *
 * **An observation stays one node.** A hit's `sps:findResult` link and the details of its blank node
 * (SPS-FIND-028) share one RDF4J [org.eclipse.rdf4j.model.BNode] within the model, also when the pod
 * placed the link and the details in different contexts. Two answers never share a blank node.
 *
 * The body, answers and the resend are [SempodsPodFind]'.
 */
class SempodsRdf4jFind private constructor(
  private val core: SempodsPodFind,
) {

  /** The hits and what the pod says about them. */
  @Throws(IOException::class)
  fun model(query: SempodsFindQuery): SempodsResponse<Model> {
    val answer = core.bytes(query, SempodsGraphFormat.N_QUADS)
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  internal companion object {

    @JvmSynthetic
    internal fun of(core: SempodsPodFind): SempodsRdf4jFind = SempodsRdf4jFind(core)
  }
}
