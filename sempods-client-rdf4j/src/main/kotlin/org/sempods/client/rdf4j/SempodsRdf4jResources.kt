package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.sempods.client.core.SempodsContent
import org.sempods.client.core.SempodsGraphFormat
import org.sempods.client.core.SempodsPodResources
import org.sempods.client.core.SempodsReadOptions
import org.sempods.client.core.SempodsResponse
import org.sempods.client.core.SempodsWriteOptions
import java.io.IOException

/**
 * [SempodsPodResources] with a [Model] for the body. [SempodsRdf4jSubjects] is the same for any IRI.
 *
 * ```java
 * SempodsResponse<Model> read = rdf.resources().getModel(event, SempodsReadOptions.of(SempodsContextSelection.of(a, b)));
 * read.getBody().contexts();   // a and b, for the statements each holds
 * ```
 *
 * **A read asks for N-Quads**, which every pod serves (SPS-CRUD-026) and which names each statement's
 * context. So every statement keeps its context, and [SempodsReadOptions.includeContexts] changes
 * nothing here. A body that does not parse as N-Quads is a
 * [org.sempods.client.core.SempodsDecodingException] with the answer's status and headers.
 *
 * **A write sends JSON-LD**, the body every pod takes. A statement with a context is sent in the named
 * graph of that context, and one without it in the default graph. The pod decides what a context other
 * than [SempodsWriteOptions.contextUri] means: the specification makes it advisory, so the statement
 * lands in the target context (SPS-CRUD-012), and the reference server answers `400`.
 *
 * Addresses, answers, the selection, the resend and the size limit are [SempodsPodResources]'.
 */
class SempodsRdf4jResources internal constructor(
  private val core: SempodsPodResources,
) {

  /** The resource's statements in the selected contexts, each with its context. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getModel(
    resourceUri: String,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<Model> {
    val answer = core.getBytes(resourceUri, SempodsGraphFormat.N_QUADS, options.withIncludeContexts(false))
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /** Replaces the resource's statements in the target context with [model] (SPS-CRUD-031). */
  @Throws(IOException::class)
  fun put(
    resourceUri: String,
    model: Model,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> =
    core.put(resourceUri, SempodsGraphFormat.JSON_LD, SempodsContent.of(Rdf4jCodec.writeJsonLd(model)), options)
}
