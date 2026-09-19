package org.sempods.client

import java.io.IOException
import java.io.OutputStream

/**
 * A pod's contexts: the catalogue at `{pod}/_system/contexts`, a context's own description at its
 * IRI, and creating one there.
 *
 * ```java
 * SempodsPodContexts contexts = pod.contexts();
 * String catalogue = contexts.listText().getBody();
 * contexts.create(tasks, SempodsContextCreate.fields().withLabel("Tasks"));
 * String description = contexts.getText(tasks).getBody();
 * ```
 *
 * **The registry answers RDF, and this module reads none.** A read is the graph the pod sent, as text
 * or as bytes: canonical JSON-LD, or N-Quads when asked for it (SPS-CTX-031). What the graph says is
 * an RDF adapter's to answer. `application/json` is never asked for: a pod may still answer the JSON
 * envelopes that predate the RDF registry there, and they are on their way out.
 *
 * **What a caller sees is the session's to decide.** The pod answers the catalogue from the request's
 * credential (SPS-CTX-021), so a session without a pod credential reads the public contexts, and a
 * deployment's own authentication stays on that request as on any other. There is no anonymous
 * variant of these methods: it would drop that authentication.
 *
 * **A read names no context.** A context is not read from a context, so no route here takes the
 * selection [SempodsPodResources] and [SempodsPodSparql] carry, and none of them sends one. What a
 * read does take is an entity tag, and a pod holds one per representation (SPS-CTX-035).
 *
 * **Answers.** Every status an operation lists is a [SempodsResponse] with its headers as the pod sent
 * them, `ETag` and `Location` included; any other is a [SempodsStatusException].
 *
 * | Operation | Answers |
 * |---|---|
 * | [listText], [listBytes], [getText], [getBytes] | `200`; `404` without a body; `304` without a body only with an entity tag |
 * | [create] | `201` for a context this call created, `200` for one that was already there (SPS-CTX-016) |
 * | [delete] | `204`; `404`; `409` for the last context the caller can see (SPS-CTX-029) |
 * | [exportTo], [export] | `200`, streamed; every other status is a [SempodsStatusException] |
 *
 * A context the session cannot see answers exactly as one that was never registered: `404`, with no
 * body and no validator.
 *
 * **What the registry holds, and what anyone says about a context, are two reads.** A description
 * carries the registry's own view — the context as a named graph, its label, its description and when
 * it was created (SPS-CTX-032) — and points at the ordinary statements about that IRI with
 * `rdfs:seeAlso`. Those are read and written through [SempodsPodSubjects], in whatever context they
 * live in.
 *
 * **Every operation takes the IRI as the pod gave it** (SPS-CTX-005, SPS-CTX-023): it is the route as
 * well as the identity, and this client composes neither. What a context may be named is the pod's to
 * say (SPS-CTX-009) — `grüße` travels percent-encoded and arrives as it was written. What is refused
 * is an IRI that could not be addressed as itself: one outside this pod's `_system/contexts/`, or
 * carrying a query, a fragment, a percent-encoded octet, a `;`, an empty or a dot segment. That is an
 * [IllegalArgumentException], and nothing is sent.
 *
 * **After a connection lost before an answer**, a read, [create] and [delete] are sent once more, as
 * any `GET`, `PUT` and `DELETE` are. A creation whose answer was lost that way reports `200`, because
 * the context is by then already there (SPS-CTX-016), and a removal `404`.
 */
class SempodsPodContexts private constructor(
  private val operations: ResourceOperations,
  private val sparql: SempodsPodSparql,
) {

  /** The catalogue as the text the pod sent, in [format], unchanged from [ifNoneMatch] with `304`. */
  @JvmOverloads
  @Throws(IOException::class)
  fun listText(
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    ifNoneMatch: String? = null,
  ): SempodsResponse<String> = operations.readAt(CATALOGUE, format.mediaType, read(ifNoneMatch), BodyReading.TEXT)

  /** The catalogue as the bytes the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun listBytes(
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    ifNoneMatch: String? = null,
  ): SempodsResponse<ByteArray> = operations.readAt(CATALOGUE, format.mediaType, read(ifNoneMatch), BodyReading.BYTES)

  /** What the registry holds for [contextUri], as the text the pod sent, in [format] (SPS-CTX-024). */
  @JvmOverloads
  @Throws(IOException::class)
  fun getText(
    contextUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    ifNoneMatch: String? = null,
  ): SempodsResponse<String> = operations.read(contextUri, format, read(ifNoneMatch), BodyReading.TEXT)

  /** What the registry holds for [contextUri], as the bytes the pod sent, in [format]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun getBytes(
    contextUri: String,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
    ifNoneMatch: String? = null,
  ): SempodsResponse<ByteArray> = operations.read(contextUri, format, read(ifNoneMatch), BodyReading.BYTES)

  /**
   * Creates the context [contextUri], or finds it: `201` for one this call created, `200` for one that
   * was there already and is left unchanged (SPS-CTX-015, SPS-CTX-016).
   *
   * [body] goes out as `application/json`; the answer is the context's description in [format], the
   * bytes the pod sent (SPS-CTX-037). A pod that cannot answer in [format] refuses the request and
   * creates nothing.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun create(
    contextUri: String,
    body: SempodsContextCreate = SempodsContextCreate.fields(),
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
  ): SempodsResponse<ByteArray> =
    operations.putAt(
      operations.pathOf(contextUri),
      SempodsContent.of(body.encoded()),
      JSON,
      format.mediaType,
      CREATED,
    )

  /**
   * Removes [contextUri]: the statements it holds and the grants that named it (SPS-CTX-017), and
   * nothing of the contexts below it (SPS-CTX-018).
   *
   * `404` for a context that was never registered, `409` for the last one this caller can see, which
   * a pod keeps so that every pod has a context (SPS-CTX-028, SPS-CTX-029). It asks for no
   * representation: the answer carries no body. A pod that refuses the removal outright answers
   * `403`, which is a [SempodsStatusException] like any other status the operation does not list.
   */
  @Throws(IOException::class)
  fun delete(contextUri: String): SempodsResponse<ByteArray> =
    operations.deleteAt(operations.pathOf(contextUri), REMOVED)

  /**
   * Everything the session may read in [contextUri], written to [out] as [format] while it arrives;
   * the body is the number of bytes written.
   *
   * `CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <contextUri> { ?s ?p ?o } }` over the pod's SPARQL route,
   * which is where a pod's graph comes from — the registry route answers what the registry holds
   * about a context, not what is in it. The query names the graph and sends no dataset parameters, so
   * a pod that does not narrow by them (SPS-SPARQL-011) answers the same graph as one that does, and
   * a context this session cannot read comes back empty rather than refused.
   *
   * **[out] is the caller's**: this writes to it and neither flushes nor closes it, whether the export
   * ends or fails. Nothing is buffered, so a context of any size passes; [SempodsBodyReader] says what
   * a reader of the stream itself may do, and what the call holds while it runs.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun exportTo(
    contextUri: String,
    out: OutputStream,
    format: SempodsGraphFormat = SempodsGraphFormat.N_QUADS,
  ): SempodsResponse<Long> = sparql.graphTo(exportQuery(contextUri), format, out)

  /** The same export, handed to [reader] as it arrives. */
  @JvmOverloads
  @Throws(IOException::class)
  fun <T : Any> export(
    contextUri: String,
    reader: SempodsBodyReader<T>,
    format: SempodsGraphFormat = SempodsGraphFormat.N_QUADS,
  ): SempodsResponse<T> = sparql.graphStream(exportQuery(contextUri), format, reader)

  private fun exportQuery(contextUri: String): String {
    // The same rule that decides whether this is a context of this pod at all. What it admits is what
    // a SPARQL `IRIREF` carries, so the IRI goes into the query as it is and closes no `<…>` early.
    operations.pathOf(contextUri)
    return "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <$contextUri> { ?s ?p ?o } }"
  }

  /** A registry read's one option, carried in the type every read shares. */
  private fun read(ifNoneMatch: String?): SempodsReadOptions = SempodsReadOptions.defaults().withIfNoneMatch(ifNoneMatch)

  internal companion object {

    @JvmSynthetic
    internal fun of(operations: ResourceOperations, sparql: SempodsPodSparql): SempodsPodContexts =
      SempodsPodContexts(operations, sparql)

    private const val CATALOGUE = ResourceAddress.RegistryPath.CATALOGUE

    private const val JSON = "application/json"

    private val CREATED = setOf(200, 201)

    private val REMOVED = setOf(204, 404, 409)
  }
}
