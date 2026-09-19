package org.sempods.client.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.OutputStream
import java.util.Collections

/**
 * SPARQL against a pod: `POST {pod}/_system/sparql/query` with the query as `application/sparql-query`
 * (SPS-SPARQL-001). The pod refuses updates and `SERVICE`, and a query sees only what the session may
 * read (SPS-SPARQL-002, SPS-SPARQL-005, SPS-SPARQL-007).
 *
 * ```java
 * SempodsPodSparql sparql = pod.sparql();
 * SempodsSparqlResults rows = sparql.select("SELECT ?s WHERE { ?s ?p ?o }").getBody();
 * boolean any = sparql.ask("ASK { ?s ?p ?o }", SempodsContextSelection.of(tasks)).getBody();
 * String quads = sparql.graphText("CONSTRUCT WHERE { ?s ?p ?o }", SempodsGraphFormat.N_QUADS).getBody();
 * ```
 *
 * **A selection is optional.** Leaving it out queries what the session may read. A restricted one is
 * sent as the SPARQL Protocol's dataset parameters: [SempodsContextSelection.none] as a present but
 * empty `default-graph-uri`, which selects nothing (SPS-SPARQL-013), and
 * [SempodsContextSelection.of] as its contexts in both `default-graph-uri` and `named-graph-uri`, so
 * `GRAPH ?g` sees them too. A pod narrows only where it accepts those parameters at all
 * (SPS-SPARQL-011); one that ignores them answers from everything the session may read, and nothing
 * tells a client which kind it faces. A long selection travels twice in the URL and can meet the
 * server's limit on it.
 *
 * **A query is sent once more after a connection lost before an answer** ([SempodsRepeatable]),
 * because running it again changes nothing.
 *
 * **Every answer but `200` is a [SempodsStatusException]** — a malformed, refused or empty query `400`,
 * a refused credential `401`, a format the query cannot produce `406` — and its message quotes neither
 * the query nor the selection. **A body is read into memory, up to 16 MiB**; a larger one is a
 * [SempodsDecodingException]. A graph too large for that is what [graphStream] and [graphTo] are for:
 * they hand it over as it arrives, with no limit.
 */
class SempodsPodSparql internal constructor(
  private val session: SempodsSession,
  private val exchange: Exchange,
) {

  /**
   * A SELECT query's result, read as the W3C SPARQL 1.1 Query Results JSON Format describes it.
   *
   * A document outside that format is a [SempodsDecodingException] at the member that broke it: a
   * variable declared twice, a solution binding an undeclared variable, a term whose `type` is not
   * `uri`, `literal` or `bnode`, `xml:lang` or `datatype` on anything but a literal, or both on one.
   * SPARQL 1.2's triple terms and base direction are outside it too; [resultsJson] returns such a
   * document as it is.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun select(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<SempodsSparqlResults> = exchange.run(resultsRequest(query, selection), ANSWERS, RESULTS)

  /** An ASK query's answer. A document whose `boolean` is not a JSON boolean is a [SempodsDecodingException]. */
  @JvmOverloads
  @Throws(IOException::class)
  fun ask(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<Boolean> = exchange.run(resultsRequest(query, selection), ANSWERS, BOOLEAN)

  /** A SELECT or ASK query's result document as the text the pod sent, malformed or not. */
  @JvmOverloads
  @Throws(IOException::class)
  fun resultsJson(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<String> = exchange.run(resultsRequest(query, selection), ANSWERS, BodyReading.TEXT)

  /** A SELECT or ASK query's result document as the bytes the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun resultsBytes(
    query: String,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<ByteArray> = exchange.run(resultsRequest(query, selection), ANSWERS, BodyReading.BYTES)

  /** A CONSTRUCT or DESCRIBE query's graph in [format], as the text the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun graphText(
    query: String,
    format: SempodsGraphFormat,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<String> = exchange.run(request(query, format.mediaType, selection), ANSWERS, BodyReading.TEXT)

  /** A CONSTRUCT or DESCRIBE query's graph in [format], as the bytes the pod sent. */
  @JvmOverloads
  @Throws(IOException::class)
  fun graphBytes(
    query: String,
    format: SempodsGraphFormat,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<ByteArray> = exchange.run(request(query, format.mediaType, selection), ANSWERS, BodyReading.BYTES)

  /**
   * A CONSTRUCT or DESCRIBE query's graph in [format], read by [reader] while it arrives.
   *
   * The one read here that is not bounded by 16 MiB, for a graph that does not fit in memory — a
   * context export is the case it was written for ([SempodsPodContexts.export]).
   * [SempodsBodyReader] says what the stream's lifetime is and what a reader that stops early does.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun <T : Any> graphStream(
    query: String,
    format: SempodsGraphFormat,
    reader: SempodsBodyReader<T>,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<T> = exchange.stream(request(query, format.mediaType, selection), ANSWERS, reader)

  /**
   * The same graph, written to [out] while it arrives; the body is the number of bytes written.
   *
   * **[out] is the caller's**: this writes to it and neither flushes nor closes it, whether the
   * transfer ends or fails.
   */
  @JvmOverloads
  @Throws(IOException::class)
  fun graphTo(
    query: String,
    format: SempodsGraphFormat,
    out: OutputStream,
    selection: SempodsContextSelection = SempodsContextSelection.readable(),
  ): SempodsResponse<Long> = graphStream(query, format, { body -> body.copyTo(out) }, selection)

  private fun resultsRequest(query: String, selection: SempodsContextSelection) = request(query, RESULTS_JSON, selection)

  private fun request(query: String, accept: String, selection: SempodsContextSelection): Request {
    val url = session.podBase.resolve(ROUTE).newBuilder()
    if (selection.isRestricted && selection.contextUris.isEmpty()) {
      url.addQueryParameter(DEFAULT_GRAPH, "")
    } else if (selection.isRestricted) {
      selection.contextUris.forEach { url.addQueryParameter(DEFAULT_GRAPH, it) }
      selection.contextUris.forEach { url.addQueryParameter(NAMED_GRAPH, it) }
    }
    val target = url.build().encodedQuery?.let { "$ROUTE?$it" } ?: ROUTE
    return SempodsRepeatable.mark(session.newRequest("POST", target))
      .header("Accept", accept)
      // Bytes, so the type goes out exactly as written: a String body gains `; charset=utf-8`.
      .post(query.toByteArray(Charsets.UTF_8).toRequestBody(SPARQL_QUERY))
      .build()
  }

  private companion object {

    const val ROUTE = "_system/sparql/query"

    const val RESULTS_JSON = "application/sparql-results+json"

    const val DEFAULT_GRAPH = "default-graph-uri"

    const val NAMED_GRAPH = "named-graph-uri"

    val SPARQL_QUERY = "application/sparql-query".toMediaType()

    val ANSWERS = setOf(200)

    val BOOLEAN = BodyReading<Boolean> { bytes, _ -> decodeObject(bytes).boolean("boolean") }

    val RESULTS = BodyReading<SempodsSparqlResults> { bytes, _ ->
      val document = decodeObject(bytes)
      val variables = document.nested("head").strings("vars")
      // Indexed once, and shared by every solution: the pod decides how many variables there are, and
      // a lookup that scans them per binding is quadratic within the body limit.
      val positions = HashMap<String, Int>(variables.size * 2)
      variables.forEachIndexed { index, variable ->
        if (positions.putIfAbsent(variable, index) != null) throw ProtocolViolation("/head/vars/$index: repeats an earlier variable")
      }
      val declared = Collections.unmodifiableSet(positions.keys)
      val solutions = document.nested("results").objects("bindings").mapIndexed { row, solution ->
        val bindings = LinkedHashMap<String, SempodsSparqlTerm>()
        solution.names().forEach { name ->
          val position = positions[name] ?: throw solution.violation("binds a variable not in /head/vars")
          bindings[name] = term(solution.nestedNamed(name, "/results/bindings/$row, the binding of /head/vars/$position"))
        }
        SempodsSparqlSolution.of(declared, bindings)
      }
      SempodsSparqlResults.of(variables, declared, solutions)
    }

    fun term(binding: ProtocolObject): SempodsSparqlTerm {
      if ("its:dir" in binding.names()) {
        throw binding.violation("its:dir is SPARQL 1.2's base direction, outside the SPARQL 1.1 results format")
      }
      val kind = when (binding.string("type")) {
        "uri" -> SempodsSparqlTermKind.IRI
        "literal" -> SempodsSparqlTermKind.LITERAL
        "bnode" -> SempodsSparqlTermKind.BLANK_NODE
        else -> throw binding.violation("type: expected uri, literal or bnode")
      }
      val value = binding.string("value")
      val language = binding.stringOrNull("xml:lang")
      val datatype = binding.stringOrNull("datatype")
      if (kind != SempodsSparqlTermKind.LITERAL && (language != null || datatype != null)) {
        throw binding.violation("xml:lang and datatype belong to a literal")
      }
      if (language != null && datatype != null) throw binding.violation("a literal carries xml:lang or datatype, and this one carries both")
      if (language == "") throw binding.violation("xml:lang: expected a language tag, found an empty string")
      return SempodsSparqlTerm.of(kind, value, language, datatype)
    }
  }
}
