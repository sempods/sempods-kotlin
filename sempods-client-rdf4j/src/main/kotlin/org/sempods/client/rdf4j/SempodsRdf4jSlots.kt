package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Value
import org.sempods.client.core.SempodsContent
import org.sempods.client.core.SempodsPodSlots
import org.sempods.client.core.SempodsReadOptions
import org.sempods.client.core.SempodsResponse
import org.sempods.client.core.SempodsWriteOptions
import java.io.IOException

/**
 * [SempodsPodSlots] with RDF4J values: a slot read as a [Model], and written from [Value]s.
 *
 * ```java
 * SempodsRdf4jSlots slots = rdf.slots();
 * slots.add(bob, knows, Values.iri(carol), SempodsWriteOptions.inContext(contacts));
 * slots.put(bob, name, List.of(Values.literal("Bob", "en"), Values.literal("Robert", "de")), SempodsWriteOptions.inContext(contacts));
 * Model values = slots.getModel(bob, name).getBody();
 * ```
 *
 * **A read asks for JSON-LD grouped by context** (`include_contexts=true`, SPS-CRUD-057): a slot has no
 * other representation that names where a value came from. So every statement keeps its context, and
 * [SempodsReadOptions.includeContexts] changes nothing here. A language tag comes back in lower case, as
 * JSON-LD processing leaves it; RDF compares tags without regard to case. A body that is not such a document is a
 * [org.sempods.client.core.SempodsDecodingException] with the answer's status and headers, and so is one
 * that names a remote `@context`: nothing a document names is fetched.
 *
 * **A write sends each value as a JSON-LD value object** (SPS-CRUD-023): an IRI as `@id`, a literal
 * with its lexical form and its language, or its datatype unless that is `xsd:string`. A blank node, a
 * triple term or a literal with a base direction has no value object, and is an
 * [IllegalArgumentException] before anything is sent.
 *
 * Addresses, answers, the selection, the resend and conditions are [SempodsPodSlots]'. Clearing a slot
 * and removing an edge send no value, so they stay there.
 */
class SempodsRdf4jSlots internal constructor(
  private val core: SempodsPodSlots,
) {

  /** The slot's values in the selected contexts, as statements from [subjectUri] through [predicateUri], each with its context. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getModel(
    subjectUri: String,
    predicateUri: String,
    options: SempodsReadOptions = SempodsReadOptions.defaults(),
  ): SempodsResponse<Model> {
    val answer = core.getBytes(subjectUri, predicateUri, options.withIncludeContexts(true))
    return answer.map { Rdf4jCodec.readJsonLd(it, answer.url) }
  }

  /** Replaces the slot's values in the target context with [values]; an empty collection clears it. */
  @Throws(IOException::class)
  fun put(
    subjectUri: String,
    predicateUri: String,
    values: Collection<Value>,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = core.put(subjectUri, predicateUri, SempodsContent.of(slotValueArray(values)), options)

  /** Adds [value] to the slot in the target context. */
  @Throws(IOException::class)
  fun add(
    subjectUri: String,
    predicateUri: String,
    value: Value,
    options: SempodsWriteOptions,
  ): SempodsResponse<ByteArray> = core.add(subjectUri, predicateUri, SempodsContent.of(slotValueObject(value)), options)
}
