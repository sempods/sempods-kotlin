package org.sempods.client

import com.fasterxml.jackson.databind.JsonNode
import org.sempods.media.UploadedMedia
import org.sempods.rdf.toIri
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Resource
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.Values

/**
 * **One pod, one credential.** Pod-scoped operations over HTTP — resource and slot CRUD, context
 * management, pod-owned media, pod-level reads and read-only SPARQL — against the pod at
 * [podBaseUrl], authorised by [auth]. Every method maps onto a pod HTTP endpoint; transport runs
 * through [SempodsClient] underneath.
 *
 * **The retry is why this tier exists rather than being a convenience over [SempodsClient].**
 * Retry-once-after-invalidating is a property of a bound client with a *refreshable* credential:
 * only something that can ask its credential for a fresh token can tell a rotated one from a
 * refused one. A caller passing a token per call cannot, and answers a 401 that a second attempt
 * would have satisfied. See [withTokenRetry].
 *
 * **Everything here carries pod-scoped authority, and that is what decides what belongs on it.**
 * Media writes go through the very same `PodContextWriteAuthorizer` and the very same
 * `<context>#write` / `#manage` scopes as the resource writes, so they sit here rather than on a
 * class of their own; [createContext] and [removeContext] are not RDF either — they are `_system`
 * control-plane operations on MongoDB rows — and they are here for the same reason. Pod
 * *lifecycle* is the other side of that line: creating and deleting a pod is a host-level act on
 * the admin surface, so it lives on `SempodsControlPlaneClient` and not here. [exists] is the one
 * exception, and deliberately so — see its own note.
 *
 * **A pod is addressed by its base URL, and there is no tier above this that addresses it by
 * name.** There used to be: a `SempodsDataClient` resolving pod *names* through a registry, which
 * is a shape one consumer wanted and a decentralised pod is the opposite of — the busiest consumer
 * of this client, the hosted MCP service, keyed pods by base URL all along. A consumer serving many
 * pods resolves its own names and builds one of these per pod; that resolution is a dozen lines and
 * belongs where the names come from.
 *
 * **Nothing here projects a resource onto a typed view either.** What this offers is the pod's own
 * vocabulary — a resource, a slot, a context, a query — and a consumer that wants a closed
 * predicate list builds it on top. The two tiers are alternatives a consumer picks one of, not a
 * stack it assembles; what may be added at which of them is `docs/pod-client.md`
 * §"Growing the surface — one tier at a time".
 *
 * @param podBaseUrl the pod's address, ending in `/` so [java.net.URI.resolve] extends it
 *   (`https://pods.example.com/alice/` → `https://pods.example.com/alice/_system/sparql/query`).
 * @param auth this pod's credential. [SempodsAuth.anonymous] is a supported mode, not a degraded
 *   one, and it is stated rather than defaulted into.
 */
class SempodsPodClient(
  private val podBaseUrl: URI,
  private val auth: SempodsAuth,
  private val client: SempodsClient = SempodsClient(),
) {

  private val objectMapper = client.objectMapper

  // -- contexts -----------------------------------------------------------

  /**
   * Registers [contextUri] as a context of the pod. Idempotent — returns `false` if a row already
   * exists for the same (pod, contextUri) pair. The [public] flag makes the context anonymously
   * readable and lets it surface in [publicContexts]; defaults to private.
   *
   * An application wires this from its account-creation path to materialise its public contexts;
   * nothing inside sempods core seeds app-specific contexts on its own.
   */
  fun createContext(
    contextUri: URI,
    public: Boolean = false,
    label: String? = null,
    description: String? = null,
  ): Boolean = withTokenRetry { token ->
    client.createContext(
      podBaseUrl = podBaseUrl,
      contextUri = contextUri,
      public = public,
      label = label,
      description = description,
      token = token,
    )
  }

  fun removeContext(context: URI) = withTokenRetry { token ->
    client.deleteContext(
      podBaseUrl = podBaseUrl,
      contextUri = context,
      token = token,
    )
  }

  // ─── pod-owned media ────────────────────────────────────────────────────────
  //
  // Bytes into the pod, and which contexts a media is visible through. Nothing here writes a
  // triple: whoever wants a `schema:ImageObject` writes it themselves and points its
  // `schema:contentUrl` at [UploadedMedia.contentUrl], so registry and graph stay unaware of each
  // other and there is no synchronisation path to drift.
  //
  // **Reads are deliberately absent.** A media is fetched by dereferencing its content URL like any
  // other web resource — that is the whole point of putting the pod's own address in `contentUrl` —
  // so a `downloadMedia` here would be a second way to do what an `<img>` tag already does.
  //
  // A pod server that configures no media backend serves none of these routes at all; see
  // `docs/media.md` §"The seam".

  /**
   * Store [source]'s bytes in the pod and assign them to [context].
   *
   * **[source] is a supplier and never a bare `InputStream`, and that is load-bearing rather than
   * stylistic.** [withTokenRetry] re-executes its whole block once after a `401`, which rests on
   * the wrapped operation being idempotent, so the body is opened once per attempt. Handed a stream
   * instead, the second attempt would send a consumed one — and since the request itself would
   * still succeed, the pod would store an empty object and answer `201` for it. A failure that
   * looks like success is the one this signature buys off. Each call must therefore return a
   * **fresh** stream positioned at the first byte.
   *
   * The id is the content hash, so this is idempotent per pod: the same bytes uploaded twice are one
   * object with two context assignments, and the second upload returns the first one's id and URL.
   *
   * @param contentType what this context claims the bytes are. Recorded per assignment and served by
   *   the content route, so it has to be a parsable media type — the pod may refuse otherwise.
   * @param size the exact length of what [source] yields, when the caller knows it. A courtesy for
   *   the transport (a definite `Content-Length` instead of a chunked body), never a control: the
   *   pod enforces its own size limit while reading, because a declared length is forgeable.
   * @param filename the caller's own name for it. It describes *their* assignment and never decides
   *   how anything is served.
   */
  fun uploadMedia(
    context: URI,
    contentType: String,
    source: () -> InputStream,
    size: Long? = null,
    filename: String? = null,
  ): UploadedMedia = withTokenRetry { token ->
    client.uploadMedia(
      podBaseUrl = podBaseUrl,
      contextUri = context,
      contentType = contentType,
      body = source,
      size = size,
      filename = filename,
      token = token,
    )
  }

  /**
   * [uploadMedia] for a file on disk — the common case, and the one the store seam already speaks.
   *
   * A file is a supplier that opens it plus a size that is simply known, so the two forms cannot
   * drift.
   */
  fun uploadMedia(
    context: URI,
    contentType: String,
    source: Path,
    filename: String? = null,
  ): UploadedMedia = uploadMedia(
    context = context,
    contentType = contentType,
    source = { Files.newInputStream(source) },
    size = Files.size(source),
    filename = filename,
  )

  /**
   * Store what [sourceUrl] serves, fetched **by the pod** rather than by the caller, and assign it to
   * [context].
   *
   * For the case where the caller holds a URL and not the bytes — a signed Drive or S3 link the
   * connector was handed. The content type is the source's own; there is deliberately no way to
   * override it, since that would let a caller have the pod serve an SVG as `image/png`.
   *
   * **This is the dangerous half of the media surface**, and everything that makes it safe lives on
   * the server: the address the pod connects to is validated and pinned, every redirect hop is
   * checked again, and the size limit is enforced while streaming. A refusal is a deliberately
   * undifferentiated failure — the pod does not report *why* it would not fetch a URL, because that
   * would map the network it sits in.
   */
  fun uploadMediaFromUrl(
    context: URI,
    sourceUrl: URI,
    filename: String? = null,
  ): UploadedMedia = withTokenRetry { token ->
    client.uploadMediaFromUrl(
      podBaseUrl = podBaseUrl,
      contextUri = context,
      sourceUrl = sourceUrl,
      filename = filename,
      token = token,
    )
  }

  /**
   * Make an existing media visible through [context] as well — one upload, two places, one copy of
   * the bytes.
   *
   * Idempotent: assigning a context the media already carries changes nothing.
   *
   * **Two permissions, and the second is the point:** write on [context] *and* read on a context the
   * media already carries. Without the read check, anyone holding write anywhere could attach an
   * arbitrary media id — the id is a content hash — to their own context and read it back from
   * there. The pod fails rather than assigning when it cannot establish both.
   */
  fun assignMedia(mediaId: String, context: URI) = withTokenRetry { token ->
    client.assignMedia(
      podBaseUrl = podBaseUrl,
      mediaId = mediaId,
      contextUri = context,
      token = token,
    )
  }

  /**
   * Drop [context]'s claim on a media. When it was the last one, the pod stamps the media
   * unreferenced and its bytes become a candidate for the deployment's sweep — nothing is deleted
   * here, and that grace period is what the whole reference counting is for.
   *
   * **Ensure-absent, so a media this pod never held is not an error.** Reporting one would turn the
   * operation into an existence oracle: hold write on any one context, pass the hash of a file you
   * have, and the outcome would say whether the pod holds it — including in contexts you cannot
   * read. The price is that a typo succeeds silently, which is the ordinary trade for an idempotent
   * removal.
   */
  fun unassignMedia(mediaId: String, context: URI) = withTokenRetry { token ->
    client.unassignMedia(
      podBaseUrl = podBaseUrl,
      mediaId = mediaId,
      contextUri = context,
      token = token,
    )
  }

  // -- resource CRUD ------------------------------------------------------

  /**
   * PUTs the outgoing edges of [resourceUri] into [contextUri] — replace semantics for that
   * subject in that context, the LOD layer's own contract.
   *
   * [model] is expected to hold exactly the statements of [resourceUri]; the pod stores what it is
   * sent. A subject **outside** the pod base has no PUT URL here and is a caller error rather than
   * a silent no-op — the System layer's slot route ([putSlot]) is where an external subject is
   * written, predicate by predicate.
   */
  fun putResource(resourceUri: URI, contextUri: URI, model: Model) = withTokenRetry { token ->
    client.putResource(
      podBaseUrl = podBaseUrl,
      resourceUri = resourceUri,
      contextUri = contextUri,
      model = model,
      token = token,
    )
  }

  /**
   * GETs [resourceUri] as a model, or `null` when the pod answers 404 — the resource being absent,
   * never a context of it being empty.
   *
   * The model carries statements across every context this credential can read; a caller that wants
   * one context filters client-side. That is the route's shape rather than this tier's: the LOD GET
   * has no context parameter.
   */
  fun getResource(resourceUri: URI): Model? = withTokenRetry { token ->
    client.getResource(podBaseUrl, resourceUri, token)
  }

  /**
   * Replaces one slot — every value of `(subjectUri, predicateUri)` in [contextUri] — through the
   * System layer. An empty [values] clears the slot.
   *
   * **The way to write a subject the pod does not host.** External URIs are first-class here and
   * refused by [putResource], which is the difference between the two layers rather than a
   * limitation of either (`docs/lod-crud/`).
   */
  fun putSlot(subjectUri: URI, predicateUri: URI, contextUri: URI, values: List<Value>) =
    withTokenRetry { token ->
      client.putSlot(
        podBaseUrl = podBaseUrl,
        subjectUri = subjectUri,
        predicateUri = predicateUri,
        contextUri = contextUri,
        values = values,
        token = token,
      )
    }

  // Note: deleting a resource does not cascade to resources it references — callers that own the
  // lifecycle pass referencePredicates at the
  // application layer, where the set of referenced resources is known.
  fun delete(resourceUri: URI, contextUri: URI) = withTokenRetry { token ->
    if (!isUnderPodBase(resourceUri)) {
      // External subjects (e.g. an offer keyed by its ticket-shop URI) have no DELETE
      // URL under the pod — clear every predicate the subject carries in the context
      // through the slot endpoint (an empty value array clears the slot; afterwards
      // the resource has no statements left in the context, the same end state as
      // the LOD DELETE).
      client.sparqlSelectColumn(
        podBaseUrl,
        "SELECT DISTINCT ?p WHERE { GRAPH <$contextUri> { <$resourceUri> ?p ?o } }",
        column = "p",
        token = token,
      ).forEach { predicate ->
        client.putSlot(
          podBaseUrl = podBaseUrl,
          subjectUri = resourceUri,
          predicateUri = URI(predicate),
          contextUri = contextUri,
          values = emptyList(),
          token = token,
        )
      }
    } else {
      client.deleteResource(
        podBaseUrl = podBaseUrl,
        resourceUri = resourceUri,
        contextUri = contextUri,
        token = token,
      )
    }
  }

  // -- SPARQL-backed read queries -----------------------------------------

  fun existsResource(
    type: URI,
    resourceUri: URI,
    contexts: Collection<URI>? = null,
  ): Boolean {
    // Null and empty mean different things, and the difference is load-bearing:
    //   contexts == null  → no context filter (any readable context)
    //   contexts.isEmpty → matches nothing, so the answer is `false`
    //                       without a server round trip
    // `PodRepository.existsResource` on the server reaches the same outcome by
    // treating an empty context set as matching nothing. Conflating these two
    // cases lets a caller's context sync skip valid deletions when its set of other
    // source contexts is empty.
    if (contexts != null && contexts.isEmpty()) {
      return false
    }
    return withTokenRetry { token ->
      val typeIri = type.toString()
      val resource = resourceUri.toString()
      val graphFilter = if (contexts == null) {
        ""
      } else {
        val values = contexts.joinToString(separator = " ") { "<${it}>" }
        "VALUES ?g { $values } "
      }
      val query = "ASK { ${graphFilter}GRAPH ?g { <$resource> a <$typeIri> } }"
      client.sparqlAsk(podBaseUrl, query, token)
    }
  }

  fun findReferencingResources(contextUri: URI, objectUri: URI): Set<URI> =
    withTokenRetry { token ->
      val query =
        "SELECT DISTINCT ?s WHERE { GRAPH <$contextUri> { ?s ?p <$objectUri> } }"
      client.sparqlSelectColumn(podBaseUrl, query, column = "s", token = token)
        .mapNotNullTo(mutableSetOf()) { runCatching { URI(it) }.getOrNull() }
    }

  // ─── queries the caller builds itself ───────────────────────────────────────
  //
  // The methods above ask fixed questions. These two carry a question only the caller knows, and
  // they know nothing about it: a query string, a passthrough.
  //
  // **That generality is the point, not a concession.** `docs/modularity.md` §"The service
  // contract is semantic, not a facade over RDF" forbids a method whose *name* encodes an app's
  // question — a `findReferencingMedia` would move one app's rule into every pod. Without a raw
  // passthrough, giving such a media check the retry below would have required exactly such a
  // method. So this is the rule's positive form: an app's rules stay in the app's query text, and
  // what the client offers is the endpoint.
  //
  // What they add over reaching for [SempodsClient] directly is [withTokenRetry], and that is not a
  // convenience: a caller holding its own token has no way to notice one that was rotated
  // mid-flight, and a dead bearer answers 401 rather than "no results".
  //
  // Deliberately only these two: `sparqlAsk` and `sparqlSelectColumn` have no caller outside this
  // class, and unused raw-query surface is untested surface. And deliberately *one* raw method
  // rather than a raw twin per route — the tier rule in `docs/pod-client.md` §"Growing the
  // surface — one tier at a time", which also says a typed method replaces a raw one here instead of
  // joining it. [sparqlSelect] is the raw one still standing because nothing typed can take its
  // place yet: `SparqlResult` carries matched IRIs plus their model, and a consumer's keyset
  // pagination reads the exact lexical of its sort key out of the bindings, which no
  // resource-shaped result carries.

  /**
   * SPARQL `SELECT` against `{pod}/_system/sparql/query`, scoped by the credential, with the 401
   * retry. Returns the SPARQL-Results-JSON body **verbatim** — the caller wrote the query and owns
   * the shape of its bindings, so parsing and re-serialising here would only be a chance to lose
   * something.
   *
   * **Safe under [withTokenRetry] because the server forbids the alternative, not by convention:**
   * `SparqlQueryService` rejects every Update form and refuses `SERVICE` anywhere in the algebra, so
   * there is no query a retry could re-execute into a state change.
   *
   * The retry may nevertheless return a *different* answer than the first attempt would have — a
   * concurrent writer can change the graph in between. That is the contract [withTokenRetry] states
   * for everything it wraps ("reaches the same end state", not "returns the same value") and not a
   * violation of it; these are reads, not snapshots.
   */
  fun sparqlSelect(query: String): String =
    withTokenRetry { token -> client.sparqlSelect(podBaseUrl, query, token) }

  /**
   * SPARQL `CONSTRUCT` against the same endpoint, with the same retry and the same idempotence
   * argument as [sparqlSelect] — an RDF model rather than results JSON.
   *
   * The answer is requested as n-quads, so **named graphs survive**: a constructed statement keeps
   * the context it came from, which is what a caller reading per-context state depends on.
   */
  fun sparqlConstruct(query: String): Model =
    withTokenRetry { token -> client.sparqlConstruct(podBaseUrl, query, token) }

  /**
   * SPARQL `ASK`, with the same retry and the same idempotence argument as [sparqlSelect].
   */
  fun sparqlAsk(query: String): Boolean =
    withTokenRetry { token -> client.sparqlAsk(podBaseUrl, query, token) }

  /**
   * [sparqlSelect] for the common single-column case: the bound values of [column] across every
   * row, in result order, unbound cells skipped.
   *
   * The typed form of that route at this tier, so a caller asking a one-column question does not
   * carry a results-JSON parser of its own — see `docs/pod-client.md` §"Growing the surface
   * — one tier at a time".
   */
  fun sparqlSelectColumn(query: String, column: String): List<String> =
    withTokenRetry { token -> client.sparqlSelectColumn(podBaseUrl, query, column, token) }

  /**
   * A `SELECT ?s ?p ?o ?g` read back as statements, **with the named graph each came from**.
   *
   * The typed form for a caller that projects a graph rather than reading a column, and the reason
   * it is not [sparqlConstruct]: `CONSTRUCT` drops the context, which is exactly the coordinate such
   * a caller reads by. Rows that do not bind `?s`, `?p` and `?o` to usable terms are skipped —
   * blank nodes are forbidden in pod data, so a bnode in any position is dropped rather than
   * invented.
   */
  fun sparqlSelectStatements(query: String): List<Statement> =
    parseStatements(sparqlSelect(query))

  /**
   * **The one lifecycle question that belongs on the data path**, which is why it is here and pod
   * creation and deletion are not.
   *
   * Anonymous, and deliberately NOT the admin route: existence is asked on the data path (migration
   * guards, the public events listing), and answering it with a host-level admin credential would
   * put that authority in pod-scoped code. `_system/meta/date-modified` 404s for an unknown pod, so
   * no credential is needed at all — [auth] is never consulted — and it separates the cases the
   * timestamp itself cannot: 404 only for an unknown pod, 200 with a null timestamp for one that
   * exists but was never written to.
   *
   * `PodControlPlaneClient.existsPod` asks the same question over the admin route. The duplication
   * is deliberate: the two carry different authority, and a provisioning path must not learn to
   * answer it without one.
   */
  fun exists(): Boolean = client.podExists(podBaseUrl)

  /**
   * The pod's stored last-modified timestamp, backing a consumer's listing ETag.
   *
   * `GET /{pod}/_system/meta/date-modified` surfaces the pod's stored timestamp; 404 → null for an
   * unknown pod, and [exists] disambiguates. Read **anonymously**: the
   * timestamp is not context-scoped (already public via that ETag) and the endpoint serves it
   * without a bearer, so [auth] is never consulted — which also means a not-yet-provisioned pod (no
   * credential) does not error.
   */
  fun lastModifiedAt(): Instant? = client.fetchPodLastModifiedAt(podBaseUrl)

  /**
   * Asked **anonymously**, which is what makes the answer the public contexts.
   *
   * `GET {pod}/_system/contexts` returns what the caller may see, and a caller without a bearer
   * may see exactly the public ones (`SempodsBaseEndpoint.authenticate` resolves a missing bearer
   * to `restrictedContexts = publicContexts`, and the endpoint filters its listing by that). So
   * there is nothing to filter here — asking without a credential *is* the filter.
   *
   * Deliberately not routed through [withTokenRetry], and [auth] is not consulted at all: a
   * consumer of this method may hold no credential, and using one would narrow the answer rather
   * than widen it — a bearer without `public-read` sees only its own grants.
   */
  fun publicContexts(): Set<URI> =
    client.listContexts(podBaseUrl, token = null).toSet()

  /**
   * The registered contexts of the pod **visible to [auth]**. `GET /{pod}/_system/contexts` lists
   * what the credential can see, filtered server-side by its effective permissions. The registry
   * behind it holds every context, public and private; for an app's `<app-root>#manage` token the
   * answer is ONLY the sandbox under `apps/<app>/...`. Unknown pod → empty set.
   *
   * **Visibility caveat:** a sandbox-scoped token cannot observe contexts outside its sandbox here.
   * Callers must not use this to reason about out-of-sandbox state (e.g. detecting a registered
   * out-of-root *legacy* context) when they hold only a scoped token — those contexts are
   * invisible. What keeps this sufficient is a forward invariant the caller enforces at provision
   * time: every context an app owns lives under its `<app-root>`.
   */
  fun contexts(): Set<URI> = withTokenRetry { token ->
    client.listContexts(podBaseUrl, token).toSet()
  }

  private fun parseStatements(json: String): List<Statement> {
    val bindings = objectMapper.readTree(json).path("results").path("bindings")
    if (!bindings.isArray) return emptyList()
    val vf = SimpleValueFactory.getInstance()
    return bindings.mapNotNull { row ->
      val s = term(row.path("s")) as? Resource ?: return@mapNotNull null
      val p = term(row.path("p")) as? IRI ?: return@mapNotNull null
      val o = term(row.path("o")) ?: return@mapNotNull null
      val g = term(row.path("g")) as? Resource
      vf.createStatement(s, p, o, g)
    }
  }

  /** SPARQL-Results-JSON term → RDF4J value. Blank nodes are forbidden in pod data, so they are dropped. */
  private fun term(node: JsonNode): Value? {
    if (node.isMissingNode) return null
    val value = node.path("value").takeIf { !it.isMissingNode }?.asText() ?: return null
    return when (node.path("type").asText()) {
      "uri" -> Values.iri(value)
      "literal", "typed-literal" -> {
        val lang = node.path("xml:lang").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        val datatype = node.path("datatype").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        when {
          !lang.isNullOrBlank() -> Values.literal(value, lang)
          !datatype.isNullOrBlank() -> Values.literal(value, Values.iri(datatype))
          else -> Values.literal(value)
        }
      }
      else -> null
    }
  }

  /**
   * Runs [block] with this pod's credential. On a single 401 — the token expired or was
   * rotated/revoked mid-flight, which a provider's refresh margin narrows but cannot rule
   * out — [SempodsAuth.invalidate] drops the cached credential, a fresh one is acquired, and the
   * operation runs exactly once more; a second 401 (or any non-401 [SempodsClientException])
   * propagates. The wrapped data operations are idempotent (PUT/DELETE/SPARQL, slot replace,
   * RDF model dedup), so re-executing the whole block on retry reaches the same end state.
   */
  private inline fun <R> withTokenRetry(block: (token: String?) -> R): R {
    val token = auth.token()
    return try {
      block(token)
    } catch (e: SempodsClientException) {
      if (e.statusCode != 401) throw e
      // Nothing to re-mint for an anonymous caller: a 401 there means the operation needs a
      // credential this consumer does not have, and retrying would only ask twice.
      if (token == null) throw e
      auth.invalidate()
      block(auth.token())
    }
  }

  /** Same prefix rule as `SempodsClient.resourcePathRelativeToPod`. */
  private fun isUnderPodBase(resourceUri: URI): Boolean {
    val podBase = podBaseUrl.toString().trimEnd('/') + "/"
    return resourceUri.toString().startsWith(podBase)
  }
}
