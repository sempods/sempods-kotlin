package org.sempods.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Words to resource IRIs: `POST {pod}/_system/find` with the query as a JSON body (SPS-FIND-004).
 * The answer is an RDF graph of the hits and what the pod chose to say about them, which a caller can
 * merge into its own graph and follow links from (SPS-FIND-003).
 *
 * ```java
 * SempodsPodFind find = pod.find();
 * String hits = find.text(SempodsFindQuery.text("harbour")).getBody();
 * String grouped = find.text(SempodsFindQuery.text("harbour").withIncludeContexts(true),
 *     SempodsGraphFormat.N_QUADS).getBody();
 * ```
 *
 * **The body carries only what the query sets.** `text` is always sent; `type` when the query names
 * types; `contexts` when the selection is restricted, so [SempodsContextSelection.none] goes out as an
 * empty list and the pod answers it with an empty graph (SPS-FIND-015); `include_contexts` when it is
 * `true`; `limit` when it is set.
 *
 * **The answer is the pod's, unparsed.** A hit may come with observation nodes linked through
 * `sps:findResult` (SPS-FIND-028), and the grouped form places every statement in a named context
 * (SPS-FIND-027). Blank-node labels are local to one answer: the text keeps them, and an RDF reader
 * of the whole answer keeps them apart from another answer's.
 *
 * **A find is sent once more after a connection lost before an answer** ([SempodsRepeatable]),
 * because searching again changes nothing.
 *
 * **Every answer but `200` is a [SempodsStatusException]** — a blank text or a malformed member `400`,
 * a refused credential `401`, an unknown pod `404`, a format the pod does not produce `406` — and its
 * message quotes neither the text nor the selection. **A body is read into memory, up to 16 MiB**; a
 * larger one is a [SempodsDecodingException].
 */
class SempodsPodFind private constructor(
  private val session: SempodsSession,
  private val exchange: Exchange,
) {

  /** The hits in [format], JSON-LD unless given, as the text the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun text(
    query: SempodsFindQuery,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
  ): SempodsResponse<String> = exchange.run(request(query, format), ANSWERS, BodyReading.TEXT)

  /** The hits in [format], JSON-LD unless given, as the bytes the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun bytes(
    query: SempodsFindQuery,
    format: SempodsGraphFormat = SempodsGraphFormat.JSON_LD,
  ): SempodsResponse<ByteArray> = exchange.run(request(query, format), ANSWERS, BodyReading.BYTES)

  private fun request(query: SempodsFindQuery, format: SempodsGraphFormat): Request {
    val members = LinkedHashMap<String, Any>()
    members["text"] = query.text
    if (query.types.isNotEmpty()) members["type"] = query.types
    if (query.selection.isRestricted) members["contexts"] = query.selection.contextUris
    if (query.includeContexts) members["include_contexts"] = true
    query.limit?.let { members["limit"] = it }
    return SempodsRepeatable.mark(session.newRequest("POST", ROUTE))
      .header("Accept", format.mediaType)
      // Bytes, so the type goes out exactly as written: a String body gains `; charset=utf-8`.
      .post(encodeObject(members).toRequestBody(JSON))
      .build()
  }

  internal companion object {

    @JvmSynthetic
    internal fun of(session: SempodsSession, exchange: Exchange): SempodsPodFind = SempodsPodFind(session, exchange)

    private const val ROUTE = "_system/find"

    private val JSON = "application/json".toMediaType()

    private val ANSWERS = setOf(200)
  }
}
