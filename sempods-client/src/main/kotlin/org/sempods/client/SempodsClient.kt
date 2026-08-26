package org.sempods.client

import org.sempods.commons.net.SempodsPodRoutes
import org.sempods.media.PodMediaSource
import org.sempods.media.UploadedMedia
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/**
 * Thin HTTP client for the **pod surface** — `{pod}/…` and `{pod}/_system/…`, the half of a
 * deployment a specification can be written for. Each method takes the pod base URL plus an OAuth
 * bearer token; the client itself is stateless regarding auth — token acquisition and caching live
 * with the caller.
 *
 * **This is the bottom tier, and most callers want the one above it.** [SempodsPodClient] binds a
 * base URL and a [SempodsAuth] once and adds the single 401 retry, which nothing here can offer: a
 * client handed a token per call cannot tell a rotated credential from a refused one.
 *
 * Coverage is the slice of the pod's surface that maps onto existing pod HTTP endpoints
 * (`PodResourceEndpoint`, `PodContextsEndpoint`, `PodMetaEndpoint`, `SparqlEndpoint`,
 * `PodMediaEndpoint`, `PodAuthEndpoint`), and it grows with the consumers that need it.
 *
 * **Pod lifecycle and app provisioning are not here.** They live on the host-level admin surface
 * (`{server}/_system/admin/pods/…`), which is proprietary to the reference implementation rather
 * than part of the pod contract, and they are authorized by a host credential rather than a
 * pod-scoped token — `SempodsControlPlaneClient` in `:sempods-control-plane-client` speaks that
 * surface. [podExists] is the one call that looks like it crossed the line and did not; see its
 * own note.
 *
 * **How this surface is allowed to grow**, so three tiers do not mean three times the methods:
 * this tier may carry *both* representations of a route ([sparqlSelect] → `String` beside
 * [sparqlConstruct] → `Model`); a bound tier keeps the typed one; the raw path is **one** generic
 * method and never a twin per route; and a typed method replaces the raw call at its tier instead of
 * joining it, when a caller needs it rather than upfront. The test is that a new pod route can be
 * added at one tier without forcing a method at the others. See `docs/pod-client.md`
 * §"Growing the surface — one tier at a time".
 *
 * **One way to construct it.** Every caller writes `SempodsClient()`; anything else to configure —
 * the HTTP client, the deadlines — is a property of the transport and is set there:
 * `SempodsClient(SempodsHttpTransport(timeouts = SempodsHttpTimeouts(…)))`. Overloads that took
 * those directly existed and had no callers, and a published client is better off with one spelling
 * than three.
 */
class SempodsClient(
  private val transport: SempodsHttpTransport = SempodsHttpTransport(),
) {

  /**
   * `internal` rather than private: [SempodsPodClient] parses SPARQL-results bodies and must not
   * build a second mapper to do it — [SempodsHttpTransport.objectMapper] exists so that a response
   * is parsed the same way wherever it is read.
   */
  internal val objectMapper = transport.objectMapper

  private fun newRequest(uri: URI, token: String? = null): SempodsRequest.Builder =
    transport.newRequest(uri, token)

  /**
   * POSTs `CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <context> { ?s ?p ?o } }` against the pod's
   * SPARQL endpoint and streams the n-quads response body into [out].
   *
   * Requires a bearer that lets the caller see the entire [contextUri] (owner token is the
   * intended caller for migration).
   */
  fun dumpContext(podBaseUrl: URI, contextUri: URI, token: String?, out: OutputStream) {
    val sparqlUrl = sparqlQueryUrl(podBaseUrl)
    val query = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <$contextUri> { ?s ?p ?o } }"
    val request = newRequest(sparqlUrl, token)
      .header("Content-Type", "application/sparql-query")
      .header("Accept", "application/n-quads")
      .POST(SempodsBody.text(query))
      .build()

    transport.sendStreaming(request) { response ->
      if (response.statusCode / 100 != 2) {
        throw SempodsClientException(
          "Dump failed: HTTP ${response.statusCode} from $sparqlUrl — ${response.bodyTextCapped()}",
          statusCode = response.statusCode,
        )
      }
      response.bodyStream().copyTo(out)
    }
  }

  /**
   * PUTs a single resource description into [contextUri]. The body is the n-quads
   * serialization of [model], which is expected to contain exactly the outgoing edges of
   * [resourceUri] (the per-resource representation, no further triples).
   *
   * Returns when the server responds 204. On any non-2xx response, throws
   * [SempodsClientException] carrying the server's body for diagnosis.
   */
  fun putResource(
    podBaseUrl: URI,
    resourceUri: URI,
    contextUri: URI,
    model: Model,
    token: String?,
  ) {
    val resourcePath = resourcePathRelativeToPod(podBaseUrl, resourceUri)
    val encodedContext = URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)
    val targetUrl = podBaseUrl.resolve("$resourcePath?context=$encodedContext")

    val body = ByteArrayOutputStream().use { buffer ->
      Rio.write(model, buffer, RDFFormat.NQUADS)
      buffer.toByteArray()
    }

    val request = newRequest(targetUrl, token)
      .header("Content-Type", "application/n-quads")
      .PUT(SempodsBody.bytes(body))
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("PUT", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * GETs a resource as n-quads. Returns the parsed model, or `null` if the server
   * answers 404. The model carries statements across all contexts the bearer can
   * read; callers that need a per-context view must filter client-side.
   */
  fun getResource(podBaseUrl: URI, resourceUri: URI, token: String?): Model? {
    // The pod base only guards here — the URL it resolves to is `resourceUri` again. Callers who
    // hold no pod base (they only have a URI) use [dereference].
    resourcePathRelativeToPod(podBaseUrl, resourceUri)
    return dereference(resourceUri, token)
  }

  /**
   * GETs a resource **by its own URI**, with no pod base and no assumption about the URL layout
   * of whoever serves it — a plain RFC 9110 dereference. Same n-quads contract as [getResource]:
   * the parsed model, or `null` on 404.
   *
   * Without a `token` the answer is whatever the server shows an anonymous caller, which on a
   * sempods pod is exactly its public contexts. That is what makes this usable against pods nobody
   * configured: reading the open web needs no credential and no prior knowledge.
   *
   * **The URI decides where this connects.** Callers passing a URI that came from a request must
   * vet it first — `SempodsHttpEventLookup` does. This client follows no redirects (the JDK default),
   * so the vetted host is the host that answers.
   */
  fun dereference(resourceUri: URI, token: String? = null): Model? {
    val request = newRequest(resourceUri, token)
      .header("Accept", "application/n-quads")
      .GET()
      .build()

    val response = transport.sendBytes(request)
    if (response.statusCode == 404) {
      return null
    }
    if (response.statusCode / 100 != 2) {
      throw SempodsClientException(
        "GET $resourceUri failed: HTTP ${response.statusCode} — " +
          response.body.toString(StandardCharsets.UTF_8),
        statusCode = response.statusCode,
      )
    }
    return Rio.parse(ByteArrayInputStream(response.body), RDFFormat.NQUADS)
  }

  /**
   * DELETEs a resource from [contextUri]. [SempodsPodClient.delete] is the bound form, and adds
   * the external-subject branch this route has no URL for.
   */
  fun deleteResource(podBaseUrl: URI, resourceUri: URI, contextUri: URI, token: String?) {
    val resourcePath = resourcePathRelativeToPod(podBaseUrl, resourceUri)
    val encodedContext = URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)
    val targetUrl = podBaseUrl.resolve("$resourcePath?context=$encodedContext")

    val request = newRequest(targetUrl, token)
      .DELETE()
      .build()

    val response = transport.send(request)
    // 404 is acceptable on delete — the route is idempotent and no-ops on a missing row.
    // Any other non-2xx is a real failure.
    if (response.statusCode == 404) {
      return
    }
    if (response.statusCode / 100 != 2) {
      throw transport.failure("DELETE", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * PUTs to `{pod}/_system/contexts/{path}` to register [contextUri] as a
   * managed context. Returns `true` if the server created a new row (201),
   * `false` if the row already existed (200 — PUT is idempotent). Any other
   * status throws. [SempodsPodClient.createContext] is the bound form of the same contract.
   *
   * [contextUri] must be a canonical pod-context URI of the shape
   * `{podBaseUrl}_system/contexts/{path}` — identity and management route are the same
   * string, so the URI is what we PUT against.
   *
   * The endpoint accepts the pod owner or a service client whose `manage` scope
   * covers the context (see `PodContextsEndpoint.put` and
   * `docs/auth/service-clients.md`).
   */
  fun createContext(
    podBaseUrl: URI,
    contextUri: URI,
    public: Boolean,
    label: String?,
    description: String?,
    token: String?,
  ): Boolean {
    val targetUrl = contextManagementUrl(podBaseUrl, contextUri)
    val payload = mutableMapOf<String, Any?>("public" to public)
    if (label != null) payload["label"] = label
    if (description != null) payload["description"] = description
    val body = objectMapper.writeValueAsBytes(payload)

    val request = newRequest(targetUrl, token)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .PUT(SempodsBody.bytes(body))
      .build()

    val response = transport.send(request)
    return when (response.statusCode) {
      201 -> true
      200 -> false
      else -> throw transport.failure("PUT", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * DELETEs a context via `{pod}/_system/contexts/{path}`. 404 is treated as
   * success: the route is idempotent, no-opping on an unknown context.
   */
  fun deleteContext(podBaseUrl: URI, contextUri: URI, token: String?) {
    val targetUrl = contextManagementUrl(podBaseUrl, contextUri)

    val request = newRequest(targetUrl, token)
      .DELETE()
      .build()

    val response = transport.send(request)
    if (response.statusCode == 404) {
      return
    }
    if (response.statusCode / 100 != 2) {
      throw transport.failure("DELETE", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * GETs `{pod}/_system/contexts` and returns the canonical URIs of the contexts
   * the bearer can see. The server filters the listing by the token's effective
   * permissions (`PodContextsEndpoint.list`), so a `<root>#manage` service token
   * sees the contexts under its sandbox — which is what a provisioned app needs.
   *
   * [SempodsPodClient.contexts] is the bound form, answering with a set; order is not
   * significant here either. Any non-2xx throws [SempodsClientException].
   */
  fun listContexts(podBaseUrl: URI, token: String?): List<URI> {
    val targetUrl = podBaseUrl.resolve(SempodsPodRoutes.CONTEXTS)

    val request = newRequest(targetUrl, token)
      .header("Accept", "application/json")
      .GET()
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("GET", targetUrl, response.statusCode, response.body)
    }
    val contexts = objectMapper.readTree(response.body).path("contexts")
    if (!contexts.isArray) return emptyList()
    return contexts.mapNotNull { node ->
      node.path("context_iri").takeIf { it.isTextual }?.asText()?.let(::URI)
    }
  }

  // ─── media (`{pod}/_system/media/…`) ──────────────────────────────────────────
  //
  // Pod-scoped like the routes above, but these exist only where the deployment configured a media
  // backend — with none, the whole subtree is absent and every call here answers 404. That is
  // deliberately **not** softened into a no-op anywhere below: a pod that serves no media at all is a
  // different situation from one that had nothing to do, and only the first is a misconfiguration.

  /**
   * `POST {pod}/_system/media?context=…` with the bytes in the body — the raw upload path.
   *
   * **[body] is re-invocable on purpose** and is invoked once per attempt, so the caller's retry can
   * repeat the request; each call must yield a fresh stream. That is why this takes a supplier rather
   * than the stream the JDK client would otherwise be handed.
   *
   * [size] only decides the framing: with it the request carries a definite `Content-Length`, without
   * it a chunked body. The pod enforces its own limit while reading either way, so this is a
   * courtesy to the transport rather than something the server trusts.
   *
   * The route answers `201` whether or not the pod already held these bytes; the response says where
   * the object is and nothing about it.
   */
  fun uploadMedia(
    podBaseUrl: URI,
    contextUri: URI,
    contentType: String,
    body: () -> InputStream,
    size: Long?,
    filename: String?,
    token: String?,
  ): UploadedMedia {
    val targetUrl = mediaCollectionUrl(podBaseUrl, contextUri, filename)
    val request = newRequest(targetUrl, token)
      .header("Content-Type", contentType)
      .header("Accept", "application/json")
      .POST(SempodsBody.stream(size, body))
      .build()

    return readUploadResponse(targetUrl, transport.send(request))
  }

  /**
   * `POST {pod}/_system/media?context=…` with a source descriptor — the pod fetches the bytes itself.
   *
   * The descriptor goes in the **body** under its own media type, not in a query parameter, and both
   * halves of that matter: source URLs are routinely credentials in themselves (a signed Drive or S3
   * link), so a query parameter would land in every access log on the way, and the upload route
   * consumes the wildcard, so only a distinct media type separates an instruction from a JSON
   * document somebody meant to store.
   */
  fun uploadMediaFromUrl(
    podBaseUrl: URI,
    contextUri: URI,
    sourceUrl: URI,
    filename: String?,
    token: String?,
  ): UploadedMedia {
    // No `?filename=` here: the descriptor carries it, and the route reads it from there.
    val targetUrl = mediaCollectionUrl(podBaseUrl, contextUri, filename = null)
    val payload = mutableMapOf<String, Any?>("source_url" to sourceUrl.toString())
    if (filename != null) payload["filename"] = filename

    val request = newRequest(targetUrl, token)
      .header("Content-Type", PodMediaSource.MEDIA_TYPE)
      .header("Accept", "application/json")
      .POST(SempodsBody.bytes(objectMapper.writeValueAsBytes(payload)))
      .build()

    return readUploadResponse(targetUrl, transport.send(request))
  }

  /**
   * `PUT {pod}/_system/media/{id}?context=…` — the context-copy. Idempotent; `204` either way.
   *
   * **A `404` throws rather than being swallowed**, unlike [deleteResource] and [deleteContext]. Here
   * it is not "there was nothing to do": the route answers `404` when the caller may not *read* the
   * media, which is a refusal, and the caller asked for an assignment that does not now exist.
   */
  fun assignMedia(podBaseUrl: URI, mediaId: String, contextUri: URI, token: String?) {
    val targetUrl = mediaUrl(podBaseUrl, mediaId, contextUri)
    val request = newRequest(targetUrl, token)
      .PUT(SempodsBody.empty())
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("PUT", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * `DELETE {pod}/_system/media/{id}?context=…` — ensure this context does not reference this media.
   *
   * The route answers `204` even for a media the pod has never seen, so there is no 404-as-success
   * case to write here: a `404` from this URL means the pod serves no media surface at all, and
   * treating that as "removed" would hide a deployment holding assignments nothing can reach.
   */
  fun unassignMedia(podBaseUrl: URI, mediaId: String, contextUri: URI, token: String?) {
    val targetUrl = mediaUrl(podBaseUrl, mediaId, contextUri)
    val request = newRequest(targetUrl, token)
      .DELETE()
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("DELETE", targetUrl, response.statusCode, response.body)
    }
  }

  /**
   * Both upload paths end here: a `2xx` carrying `id` and `content_url`, or a failure.
   *
   * `content_url` is read rather than rebuilt from [mediaId][UploadedMedia.mediaId] — the pod knows
   * the address it is published at, this client only knows the one it dialled, and the value ends up
   * in a persisted `schema:contentUrl`.
   */
  private fun readUploadResponse(targetUrl: URI, response: SempodsResponse<String>): UploadedMedia {
    if (response.statusCode / 100 != 2) {
      throw transport.failure("POST", targetUrl, response.statusCode, response.body)
    }
    val root = objectMapper.readTree(response.body)
    return UploadedMedia(
      mediaId = transport.requiredText(root, "id", targetUrl, response.body),
      contentUrl = URI(transport.requiredText(root, "content_url", targetUrl, response.body)),
    )
  }

  private fun mediaCollectionUrl(podBaseUrl: URI, contextUri: URI, filename: String?): URI {
    val query = StringBuilder("?context=").append(URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8))
    if (filename != null) query.append("&filename=").append(URLEncoder.encode(filename, StandardCharsets.UTF_8))
    return podBaseUrl.resolve("${SempodsPodRoutes.MEDIA}$query")
  }

  /**
   * A media's own URL with the context it is being assigned to or removed from.
   *
   * [mediaId] goes through [urlPathSegment] although a well-formed one is base64url and survives it
   * unchanged: the id arrives from a caller, and a segment builder that only works for well-formed
   * input is a path-traversal waiting for the first malformed one.
   */
  private fun mediaUrl(podBaseUrl: URI, mediaId: String, contextUri: URI): URI {
    val encodedContext = URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)
    return podBaseUrl.resolve("${SempodsPodRoutes.MEDIA_PATH_PREFIX}${urlPathSegment(mediaId)}?context=$encodedContext")
  }

  /**
   * GETs `{pod}/_system/meta/date-modified` and returns the pod's `dateModified`, or
   * `null` when the pod has no recorded write (JSON `dateModified: null`) or does not
   * exist (404). Both cases map to null on purpose, so the caller disambiguates "empty" from
   * "missing" via the lifecycle `existsPod` rather than by reading the status code.
   *
   * [token] is optional: the pod-level timestamp is not context-scoped (it backs a
   * public ETag) and the endpoint serves it anonymously, so a consumer reads it without a
   * service token — which also means it works for a not-yet-provisioned pod that has no
   * credential to mint one. Pass a bearer only to authenticate the read (e.g. for audit).
   *
   * [SempodsPodClient.lastModifiedAt] is the bound form of the same contract.
   */
  fun fetchPodLastModifiedAt(podBaseUrl: URI, token: String? = null): Instant? {
    val targetUrl = podBaseUrl.resolve(SempodsPodRoutes.META_DATE_MODIFIED)

    val request = newRequest(targetUrl, token)
      .header("Accept", "application/json")
      .GET()
      .build()

    val response = transport.send(request)
    if (response.statusCode == 404) {
      return null
    }
    if (response.statusCode / 100 != 2) {
      throw transport.failure("GET", targetUrl, response.statusCode, response.body)
    }
    val value = objectMapper.readTree(response.body).path("dateModified")
    return value.takeIf { it.isTextual }?.asText()?.let(Instant::parse)
  }

  /**
   * Runs a SPARQL `ASK` against `{pod}/_system/sparql/query` and returns the
   * boolean result. The query body is sent as `application/sparql-query`.
   */
  fun sparqlAsk(podBaseUrl: URI, query: String, token: String?): Boolean {
    val response = sparqlPost(
      podBaseUrl = podBaseUrl,
      query = query,
      token = token,
      accept = "application/sparql-results+json",
    )
    val root = objectMapper.readTree(response)
    val value = root.get("boolean")
      ?: throw SempodsClientException(
        "SPARQL ASK response missing 'boolean' field: ${response.take(200)}"
      )
    return value.asBoolean()
  }

  /**
   * Runs a SPARQL `SELECT` and returns the raw SPARQL-Results-JSON body. Callers
   * are responsible for navigating bindings — keeping the parser thin avoids
   * leaking a binding model into the client API.
   */
  fun sparqlSelect(podBaseUrl: URI, query: String, token: String?): String {
    return sparqlPost(
      podBaseUrl = podBaseUrl,
      query = query,
      token = token,
      accept = "application/sparql-results+json",
    )
  }

  /**
   * Convenience over [sparqlSelect] for the common single-column case: returns
   * the bound values for [column] across every row, in result order. Unbound
   * cells are skipped.
   */
  fun sparqlSelectColumn(
    podBaseUrl: URI,
    query: String,
    column: String,
    token: String?,
  ): List<String> {
    val json = sparqlSelect(podBaseUrl, query, token)
    val bindings = objectMapper.readTree(json).path("results").path("bindings")
    if (!bindings.isArray) return emptyList()
    return bindings.mapNotNull { row ->
      row.path(column).path("value").takeIf { !it.isMissingNode && !it.isNull }?.asText()
    }
  }

  /**
   * Runs a SPARQL `CONSTRUCT`/`DESCRIBE` and returns the resulting model. The
   * request asks for n-quads so context information survives.
   */
  fun sparqlConstruct(podBaseUrl: URI, query: String, token: String?): Model {
    val sparqlUrl = sparqlQueryUrl(podBaseUrl)
    val request = newRequest(sparqlUrl, token)
      .header("Content-Type", "application/sparql-query")
      .header("Accept", "application/n-quads")
      .POST(SempodsBody.text(query))
      .build()

    val response = transport.sendBytes(request)
    if (response.statusCode / 100 != 2) {
      throw SempodsClientException(
        "SPARQL CONSTRUCT failed: HTTP ${response.statusCode} from $sparqlUrl — " +
          response.body.toString(StandardCharsets.UTF_8),
        statusCode = response.statusCode,
      )
    }
    return Rio.parse(ByteArrayInputStream(response.body), RDFFormat.NQUADS)
  }

  /**
   * Replaces one slot — all values of `(subjectUri, predicateUri)` in [contextUri] — via
   * the System-layer endpoint `PUT {pod}/_system/resources/{b64url(subject)}/{b64url(predicate)}`.
   *
   * Unlike the LOD resource PUT, the System layer accepts subjects OUTSIDE the pod base
   * (external URIs are first-class there), which is what `putView` needs for
   * external-subject views (e.g. offers keyed by their ticket-shop URI).
   */
  // TODO: the semantic tier has no precondition vocabulary — this replaces a slot unconditionally,
  //  where `PodWireClient` reads an `ETag` and sends it back as `If-Match`. A caller doing
  //  read-modify-write on the `Model` side therefore still races a concurrent editor and loses
  //  last-write-wins. The wire layer has what it needs; what is missing is the typed shape at this
  //  tier (an ETag-carrying read result, and an `ifMatch` on the writes). Deliberately not invented
  //  ahead of a caller — see `docs/pod-client.md` §"Growing the surface — one tier at a
  //  time"; the first consumer that needs a safe concurrent write decides the shape.
  fun putSlot(
    podBaseUrl: URI,
    subjectUri: URI,
    predicateUri: URI,
    contextUri: URI,
    values: List<Value>,
    token: String?,
  ) {
    val encodedContext = URLEncoder.encode(contextUri.toString(), StandardCharsets.UTF_8)
    val targetUrl = podBaseUrl.resolve(
      "${SempodsPodRoutes.slot(subjectUri, predicateUri)}?context=$encodedContext"
    )
    val body = objectMapper.writeValueAsBytes(values.map(::toJsonLdValueObject))

    val request = newRequest(targetUrl, token)
      .header("Content-Type", "application/ld+json")
      .PUT(SempodsBody.bytes(body))
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw transport.failure("PUT", targetUrl, response.statusCode, response.body)
    }
  }

  /** JSON-LD value object per the slot-endpoint contract (`PodSlotWriteService`). */
  private fun toJsonLdValueObject(value: Value): Map<String, String> {
    return when (value) {
      is org.eclipse.rdf4j.model.IRI -> mapOf("@id" to value.stringValue())
      is org.eclipse.rdf4j.model.Literal -> {
        val result = mutableMapOf("@value" to value.label)
        value.language.ifPresent { result["@language"] = it }
        if (!value.language.isPresent && value.datatype.stringValue() != "http://www.w3.org/2001/XMLSchema#string") {
          result["@type"] = value.datatype.stringValue()
        }
        result
      }
      else -> throw SempodsClientException("unsupported slot value type: ${value.javaClass.simpleName}")
    }
  }

  /**
   * Mints a 2-leg service token at `{pod}/_system/auth/token` via
   * `grant_type=client_credentials`. Client credentials go into the
   * `Authorization: Basic` header, form-urlencoded before base64 per
   * RFC 6749 §2.3.1 — the pod AS url-decodes them on its side.
   *
   * No refresh token exists for this grant; callers re-mint on demand.
   * Token caching lives with the caller, behind its [SempodsAuth].
   */
  fun mintServiceToken(podBaseUrl: URI, clientId: String, clientSecret: String): ServiceTokenResponse {
    val tokenUrl = podBaseUrl.resolve(SempodsPodRoutes.AUTH_TOKEN)
    val encodedId = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
    val encodedSecret = URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
    val basic = Base64.getEncoder()
      .encodeToString("$encodedId:$encodedSecret".toByteArray(StandardCharsets.UTF_8))

    val request = newRequest(tokenUrl)
      .header("Authorization", "Basic $basic")
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(SempodsBody.text("grant_type=client_credentials"))
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw SempodsClientException(
        "Token mint failed for clientId '$clientId': HTTP ${response.statusCode} from $tokenUrl — ${response.body}",
        statusCode = response.statusCode,
      )
    }
    val root = objectMapper.readTree(response.body)
    val accessToken = transport.requiredText(root, "access_token", tokenUrl, response.body)
    val expiresIn = root.path("expires_in").takeIf { it.isNumber }?.asLong()
      ?: throw SempodsClientException(
        "Token response from $tokenUrl missing 'expires_in': ${response.body.take(200)}"
      )
    return ServiceTokenResponse(
      accessToken = accessToken,
      expiresInSeconds = expiresIn,
      scope = root.path("scope").takeIf { it.isTextual }?.asText(),
    )
  }

  /**
   * Whether [podBaseUrl] resolves to an existing pod, asked **anonymously** via
   * `{pod}/_system/meta/date-modified`.
   *
   * That route separates the two cases cleanly: 404 only for a pod the server does not know,
   * `200 {"dateModified": null}` for one that exists but was never written to. [fetchPodLastModifiedAt]
   * collapses both to `null`, which is why the existence question needs its own call.
   *
   * **No credential, and that is why this stayed here** when the rest of the pod-lifecycle
   * vocabulary moved to `SempodsControlPlaneClient`. The data path needs the answer — migration
   * guards, the public events listing — and putting a host admin secret in its way would hand
   * pod-scoped code a host-level authority for a cheap read. The control-plane client answers the
   * same question over the admin route, with that authority; two answers to one question is the
   * boundary holding, not duplication to clean up (`docs/concepts/modularity.md`).
   */
  fun podExists(podBaseUrl: URI): Boolean {
    val targetUrl = podBaseUrl.resolve(SempodsPodRoutes.META_DATE_MODIFIED)

    val request = newRequest(targetUrl)
      .header("Accept", "application/json")
      .GET()
      .build()

    val response = transport.send(request)
    if (response.statusCode == 404) {
      return false
    }
    if (response.statusCode / 100 != 2) {
      throw transport.failure("GET", targetUrl, response.statusCode, response.body)
    }
    return true
  }

  private fun urlPathSegment(value: String): String =
    transport.urlPathSegment(value)

  private fun sparqlPost(
    podBaseUrl: URI,
    query: String,
    token: String?,
    accept: String,
  ): String {
    val sparqlUrl = sparqlQueryUrl(podBaseUrl)
    val request = newRequest(sparqlUrl, token)
      .header("Content-Type", "application/sparql-query")
      .header("Accept", accept)
      .POST(SempodsBody.text(query))
      .build()

    val response = transport.send(request)
    if (response.statusCode / 100 != 2) {
      throw SempodsClientException(
        "SPARQL request failed: HTTP ${response.statusCode} from $sparqlUrl — ${response.body}",
        statusCode = response.statusCode,
      )
    }
    return response.body
  }

  private fun sparqlQueryUrl(podBaseUrl: URI): URI {
    return podBaseUrl.resolve(SempodsPodRoutes.SPARQL_QUERY)
  }

  /**
   * The management URL of a canonical context URI. Since contexts live under
   * `{podBaseUrl}_system/contexts/{path}`, identity and route are the same string and this only
   * validates that the URI is one of ours before handing it to the transport.
   *
   * Rejects URIs outside the namespace rather than papering over them: callers build context IRIs
   * via `SempodsUriBuilder.buildContext`, so a non-conforming URI is a bug at the caller. Contexts
   * in older or foreign shapes (the pre-migration `{pod}/contexts/…`, consent-created ones
   * directly under the pod root) are deliberately not manageable through this client.
   */
  private fun contextManagementUrl(podBaseUrl: URI, contextUri: URI): URI {
    val podBase = podBaseUrl.toString().trimEnd('/') + "/"
    val expectedPrefix = podBase + SempodsPodRoutes.CONTEXT_PATH_PREFIX
    val contextString = contextUri.toString()
    if (!contextString.startsWith(expectedPrefix)) {
      throw SempodsClientException(
        "Context '$contextString' is not under '$expectedPrefix' — only canonical " +
          "pod-context URIs are supported."
      )
    }
    val pathSuffix = contextString.substring(expectedPrefix.length)
    if (pathSuffix.isEmpty()) {
      throw SempodsClientException("Empty context path in '$contextString'")
    }
    return podBaseUrl.resolve(SempodsPodRoutes.context(pathSuffix))
  }

  private fun resourcePathRelativeToPod(podBaseUrl: URI, resourceUri: URI): String {
    val podBase = podBaseUrl.toString().trimEnd('/') + "/"
    val resource = resourceUri.toString()
    if (!resource.startsWith(podBase)) {
      throw SempodsClientException(
        "Resource '$resource' is not under pod base '$podBase'"
      )
    }
    return resource.substring(podBase.length)
  }
}

data class ServiceTokenResponse(
  val accessToken: String,
  val expiresInSeconds: Long,
  val scope: String?,
)

class SempodsClientException(
  message: String,
  /** HTTP status of the failed response, when the failure was a non-2xx answer (else null). */
  val statusCode: Int? = null,
  /**
   * What the server wrote in the failed response, on its own — the same excerpt [message] embeds,
   * without the method and URL in front of it.
   *
   * Separate because a caller that forwards a failure onward must not forward the URL with it.
   * [message] is written for a log line, where knowing which call failed is the point; an MCP tool
   * error goes to a language model, which has no use for `http://localhost:8090/pod/_system/…` and
   * some tendency to repeat it back to the user as if it were an address they should visit.
   * Recovering the detail by cutting [message] at a separator would be the kind of string surgery
   * that breaks silently the first time the format changes.
   *
   * Null when there was no response to read — a refused connection, or a body that could not be
   * parsed as the route's contract requires.
   */
  val responseBody: String? = null,
  /**
   * The failure underneath, when there is one — a transport refusing to connect at all rather than
   * a server answering. Callers classify on it: a blocked address and a dead credential both surface
   * as this exception, and only the cause chain tells them apart. Dropping it makes the two
   * indistinguishable, which is how a refused connection gets reported as an expired token.
   */
  cause: Throwable? = null,
) : RuntimeException(message, cause)
