package org.sempods.client.wire

import com.fasterxml.jackson.databind.JsonNode
import org.sempods.client.SempodsBody
import org.sempods.client.SempodsClientException
import org.sempods.client.SempodsHttpTransport
import org.sempods.client.SempodsRequest
import org.sempods.client.SempodsResponse
import org.sempods.commons.net.SempodsPodRoutes
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A whole-resource read: the canonical JSON-LD plus the pod's write-precondition ETag (if any). */
data class PodResource(val etag: String?, val jsonld: JsonNode)

/** A single slot read `(subject, predicate)`: the JSON-LD value array plus a slot ETag (if any). */
data class PodSlot(val etag: String?, val values: JsonNode)

/**
 * The outcome of a write: the HTTP status, the new ETag (if the pod returned one), and any JSON
 * response body — e.g. the `{outcome}` an idempotent edge removal answers with.
 */
data class PodWriteResult(val status: Int, val etag: String?, val body: JsonNode?)

/**
 * The pod's HTTP System layer, **as it is on the wire**: JSON-LD in and out as an unparsed
 * [JsonNode], the `ETag` on every read, `If-Match` / `If-None-Match` on every write.
 *
 * ### Why this exists beside `SempodsClient`
 *
 * They answer different questions about the same routes, and neither answer is a degraded version
 * of the other:
 *
 * - **This layer preserves what the pod said.** A consumer that forwards a pod's JSON-LD onward —
 *   an MCP tool handing it to a model, a proxy, anything that must not editorialise — needs the
 *   pod's own framing and `@context` intact. Parsing to RDF and re-serialising is lossy for that
 *   purpose even when it is semantically faithful, and it costs a round trip through a parser for
 *   an answer nobody is going to query.
 * - **[org.sempods.client.SempodsClient] gives meaning.** A consumer that reasons over the graph
 *   wants an RDF4J `Model`, and does not want to know that a slot is addressed by two base64url
 *   segments.
 *
 * So this is the floor and the semantic client is the storey above it — not two clients. It also
 * carries the concurrency vocabulary the semantic tier historically lacked: an ETag read here is
 * the precondition a later write sends back, which is what makes a read-modify-write safe against
 * a concurrent editor rather than last-write-wins.
 *
 * **Blocking, like everything else here.** A `suspend` consumer bridges at its own edge; see
 * `docs/pod-client.md` §"The transport" for why the client itself carries no concurrency
 * framework.
 *
 * Every method takes the pod base URL plus a bearer (`null` = anonymous, which a pod serves from
 * its public contexts). Scoping is the pod's: it sandboxes every result to the contexts the bearer
 * consented to, so an unknown or unreadable `context` filter is dropped there rather than here.
 */
class PodWireClient(
  private val transport: SempodsHttpTransport = SempodsHttpTransport(),
) {

  private val objectMapper = transport.objectMapper

  // --- reads ---------------------------------------------------------------

  /** `GET {pod}/_system/contexts` → `{ contexts[], writable_contexts[], … }`. */
  fun listContexts(podBaseUrl: URI, token: String?): JsonNode {
    val url = route(podBaseUrl, SempodsPodRoutes.CONTEXTS)
    return jsonOrFail("list_contexts", url, get(url, token, accept = JSON))
  }

  /**
   * `POST {pod}/_system/sparql/query` (SELECT/ASK) → SPARQL-Results-JSON.
   *
   * [contextIris] narrows the query's dataset and can only ever narrow it: each requested context
   * is sent as **both** `default-graph-uri` and `named-graph-uri` (SPARQL 1.1 protocol) so a
   * narrowed query keeps the same reach *within* those contexts as an un-narrowed one, and the pod
   * applies `{requested} ∩ readable`, dropping silently what the bearer may not see.
   */
  fun sparqlSelect(podBaseUrl: URI, query: String, contextIris: List<URI> = emptyList(), token: String?): JsonNode =
    sparql(podBaseUrl, query, contextIris, accept = SPARQL_RESULTS, op = "sparql_select", token = token)

  /** `POST {pod}/_system/sparql/query` (CONSTRUCT/DESCRIBE) → JSON-LD. */
  fun sparqlGraph(podBaseUrl: URI, query: String, contextIris: List<URI> = emptyList(), token: String?): JsonNode =
    sparql(podBaseUrl, query, contextIris, accept = JSON_LD, op = "sparql_graph", token = token)

  /** `POST {pod}/_system/find` → a context-sandboxed RDF subgraph as JSON-LD. */
  fun find(
    podBaseUrl: URI,
    text: String,
    types: List<URI> = emptyList(),
    contextIris: List<URI> = emptyList(),
    includeContexts: Boolean = false,
    limit: Int? = null,
    token: String?,
  ): JsonNode {
    val url = route(podBaseUrl, SempodsPodRoutes.FIND)
    val payload = linkedMapOf<String, Any>("text" to text)
    if (types.isNotEmpty()) payload["type"] = types.map(URI::toString)
    if (contextIris.isNotEmpty()) payload["contexts"] = contextIris.map(URI::toString)
    if (includeContexts) payload["include_contexts"] = true
    if (limit != null) payload["limit"] = limit

    val request = transport.newRequest(url, token)
      .header("Accept", JSON_LD)
      .header("Content-Type", JSON)
      .POST(SempodsBody.bytes(objectMapper.writeValueAsBytes(payload)))
      .build()
    return jsonOrFail("find", url, transport.send(request))
  }

  /** `GET {pod}/_system/resources/{b64url(iri)}` → the canonical JSON-LD plus the ETag header. */
  fun getResource(
    podBaseUrl: URI,
    resourceIri: URI,
    contextIris: List<URI> = emptyList(),
    includeContexts: Boolean = false,
    token: String?,
  ): PodResource {
    val query = contextIris.map { "context" to it.toString() } +
      if (includeContexts) listOf("include_contexts" to "true") else emptyList()
    val url = route(podBaseUrl, SempodsPodRoutes.resource(resourceIri), query)
    val response = get(url, token, accept = JSON_LD)
    return PodResource(response.header("ETag"), jsonOrFail("get_resource", url, response))
  }

  /** `GET {pod}/_system/resources/{b64url(subject)}/{b64url(predicate)}` → the slot's value array. */
  fun getPropertyValues(
    podBaseUrl: URI,
    subjectIri: URI,
    predicateIri: URI,
    contextIris: List<URI> = emptyList(),
    token: String?,
  ): PodSlot {
    val url = route(
      podBaseUrl,
      SempodsPodRoutes.slot(subjectIri, predicateIri),
      contextIris.map { "context" to it.toString() },
    )
    val response = get(url, token, accept = JSON_LD)
    return PodSlot(response.header("ETag"), jsonOrFail("get_property_values", url, response))
  }

  // --- writes: one pod, one context; the pod enforces the `<context>#write` scope ---

  /**
   * `PUT {pod}/_system/resources/{b64url(iri)}?context=` with the JSON-LD body — an upsert, or a
   * create-or-fail with `If-None-Match: *`. The pod echoes the post-write `ETag`, so the result
   * carries the next precondition directly and no follow-up read is needed for it.
   */
  fun createResource(
    podBaseUrl: URI,
    contextIri: URI,
    resourceIri: URI,
    jsonld: JsonNode,
    ifNoneMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "create_resource", "PUT", route(podBaseUrl, SempodsPodRoutes.resource(resourceIri)),
    contextIri, token, body = jsonld, contentType = JSON_LD, ifNoneMatch = ifNoneMatch,
  )

  /** `PATCH {pod}/_system/resources/{b64url(iri)}?context=` (RFC 7396 merge-patch). */
  fun updateResource(
    podBaseUrl: URI,
    contextIri: URI,
    resourceIri: URI,
    jsonldPatch: JsonNode,
    ifMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "update_resource", "PATCH", route(podBaseUrl, SempodsPodRoutes.resource(resourceIri)),
    contextIri, token, body = jsonldPatch, contentType = MERGE_PATCH, ifMatch = ifMatch,
  )

  /** `DELETE {pod}/_system/resources/{b64url(iri)}?context=`. */
  fun deleteResource(
    podBaseUrl: URI,
    contextIri: URI,
    resourceIri: URI,
    ifMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "delete_resource", "DELETE", route(podBaseUrl, SempodsPodRoutes.resource(resourceIri)),
    contextIri, token, ifMatch = ifMatch,
  )

  /** `POST {pod}/_system/resources/{b64url(s)}/{b64url(p)}?context=` with one JSON-LD value. */
  fun addPropertyValue(
    podBaseUrl: URI,
    contextIri: URI,
    subjectIri: URI,
    predicateIri: URI,
    value: JsonNode,
    ifMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "add_property_value", "POST", route(podBaseUrl, SempodsPodRoutes.slot(subjectIri, predicateIri)),
    contextIri, token, body = value, contentType = JSON_LD, ifMatch = ifMatch,
  )

  /** `PUT {pod}/_system/resources/{b64url(s)}/{b64url(p)}?context=` with the whole value array. */
  fun setPropertyValues(
    podBaseUrl: URI,
    contextIri: URI,
    subjectIri: URI,
    predicateIri: URI,
    values: JsonNode,
    ifMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "set_property_values", "PUT", route(podBaseUrl, SempodsPodRoutes.slot(subjectIri, predicateIri)),
    contextIri, token, body = values, contentType = JSON_LD, ifMatch = ifMatch,
  )

  /** `DELETE {pod}/_system/resources/{b64url(s)}/{b64url(p)}/{b64url(target)}?context=` (idempotent). */
  fun removePropertyValue(
    podBaseUrl: URI,
    contextIri: URI,
    subjectIri: URI,
    predicateIri: URI,
    targetIri: URI,
    token: String?,
  ): PodWriteResult = write(
    "remove_property_value", "DELETE",
    route(podBaseUrl, SempodsPodRoutes.slotValue(subjectIri, predicateIri, targetIri)),
    contextIri, token,
  )

  /** `DELETE {pod}/_system/resources/{b64url(s)}/{b64url(p)}?context=` — clears the slot. */
  fun clearPropertyValues(
    podBaseUrl: URI,
    contextIri: URI,
    subjectIri: URI,
    predicateIri: URI,
    ifMatch: String? = null,
    token: String?,
  ): PodWriteResult = write(
    "clear_property_values", "DELETE", route(podBaseUrl, SempodsPodRoutes.slot(subjectIri, predicateIri)),
    contextIri, token, ifMatch = ifMatch,
  )

  // --- plumbing ------------------------------------------------------------

  private fun sparql(
    podBaseUrl: URI,
    query: String,
    contextIris: List<URI>,
    accept: String,
    op: String,
    token: String?,
  ): JsonNode {
    val scoping = contextIris.flatMap { listOf("default-graph-uri" to it.toString(), "named-graph-uri" to it.toString()) }
    val url = route(podBaseUrl, SempodsPodRoutes.SPARQL_QUERY, scoping)
    val request = transport.newRequest(url, token)
      .header("Accept", accept)
      .header("Content-Type", SPARQL_QUERY)
      .POST(SempodsBody.text(query))
      .build()
    return jsonOrFail(op, url, transport.send(request))
  }

  private fun get(url: URI, token: String?, accept: String): SempodsResponse<String> =
    transport.send(transport.newRequest(url, token).header("Accept", accept).GET().build())

  private fun write(
    op: String,
    method: String,
    url: URI,
    contextIri: URI,
    token: String?,
    body: JsonNode? = null,
    contentType: String? = null,
    ifMatch: String? = null,
    ifNoneMatch: String? = null,
  ): PodWriteResult {
    val target = withQuery(url, listOf("context" to contextIri.toString()))
    val builder = transport.newRequest(target, token)
    ifMatch?.let { builder.header("If-Match", it) }
    ifNoneMatch?.let { builder.header("If-None-Match", it) }
    contentType?.let { builder.header("Content-Type", it) }
    val payload = body?.let { SempodsBody.bytes(objectMapper.writeValueAsBytes(it)) }
    val request = applyMethod(builder, method, payload).build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      // 412 (precondition), 403 (missing #write scope), 404 (reserved/not found) and 400 (bad
      // JSON-LD) all arrive here with the pod's own reason, so a caller sees why it was refused
      // rather than only that it was.
      throw transport.failure(op, target, response.statusCode, response.body.take(ERROR_BODY_CHARS))
    }
    val parsed = response.body.takeIf { it.isNotBlank() }?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
    return PodWriteResult(response.statusCode, response.header("ETag"), parsed)
  }

  private fun applyMethod(builder: SempodsRequest.Builder, method: String, body: SempodsBody?) =
    when (method) {
      "PUT" -> builder.PUT(body ?: SempodsBody.empty())
      "POST" -> builder.POST(body ?: SempodsBody.empty())
      "PATCH" -> builder.PATCH(body ?: SempodsBody.empty())
      "DELETE" -> builder.DELETE()
      else -> throw IllegalArgumentException("unsupported write method: $method")
    }

  private fun jsonOrFail(op: String, url: URI, response: SempodsResponse<String>): JsonNode {
    if (response.statusCode / 100 != 2) {
      throw transport.failure(op, url, response.statusCode, response.body.take(ERROR_BODY_CHARS))
    }
    return runCatching { objectMapper.readTree(response.body) }
      .getOrElse { throw SempodsClientException("$op $url returned a non-JSON body", statusCode = response.statusCode) }
  }

  private fun route(podBaseUrl: URI, path: String, query: List<Pair<String, String>> = emptyList()): URI =
    withQuery(URI("${podBaseUrl.toString().trimEnd('/')}/$path"), query)

  private fun withQuery(url: URI, query: List<Pair<String, String>>): URI {
    if (query.isEmpty()) return url
    val encoded = query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}" }
    return URI(url.toString() + (if (url.query == null) "?" else "&") + encoded)
  }

  private companion object {
    /**
     * How much of a refused response travels with the exception.
     *
     * Long enough for a whole `{"errors":[{"code","message"}]}` envelope, because the message is
     * the part a caller shows a person — and the pod puts its most useful sentences at the end of
     * it (the merge-patch 404 names `create_resource` there). A 200-character excerpt cut those off
     * mid-IRI and left the reason looking like a truncated URL.
     */
    const val ERROR_BODY_CHARS = 2000

    const val JSON = "application/json"
    const val JSON_LD = "application/ld+json"
    const val MERGE_PATCH = "application/merge-patch+json"
    const val SPARQL_QUERY = "application/sparql-query"
    const val SPARQL_RESULTS = "application/sparql-results+json"
  }
}
