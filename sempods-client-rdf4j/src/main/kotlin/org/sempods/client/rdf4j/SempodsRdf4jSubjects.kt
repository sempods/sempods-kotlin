package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.sempods.client.SempodsContent
import org.sempods.client.SempodsGraphFormat
import org.sempods.client.SempodsPodSubjects
import org.sempods.client.SempodsReadOptions
import org.sempods.client.SempodsResponse
import org.sempods.client.SempodsWriteOptions
import java.io.IOException

/**
 * [SempodsPodSubjects] with a [Model] for the body: any IRI, through the System route.
 *
 * ```java
 * rdf.subjects().put("did:web:bob.example", bob, SempodsWriteOptions.inContext(contacts));
 * ```
 *
 * What a read asks for and how a write is sent are [SempodsRdf4jResources]'; the address and answers are
 * [SempodsPodSubjects]'.
 */
class SempodsRdf4jSubjects private constructor(
  private val core: SempodsPodSubjects,
) {

  /** The subject's statements in the selected contexts, each with its context. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getModel(
    subjectUri: String,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<Model> {
    val answer = core.getBytes(subjectUri, SempodsGraphFormat.N_QUADS, options.withIncludeContexts(false))
    return answer.map { Rdf4jCodec.readNQuads(it, answer.url) }
  }

  /** Replaces the subject's statements in the target context with [model]. */
  @Throws(IOException::class)
  fun put(
    subjectUri: String,
    model: Model,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> =
    core.put(subjectUri, SempodsGraphFormat.JSON_LD, SempodsContent.of(Rdf4jCodec.writeJsonLd(model)), options)

  internal companion object {

    @JvmSynthetic
    internal fun of(core: SempodsPodSubjects): SempodsRdf4jSubjects = SempodsRdf4jSubjects(core)
  }
}
