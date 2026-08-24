package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.sempods.client.SempodsClientException
import org.sempods.client.wire.PodSlot
import org.sempods.client.wire.PodWireClient
import org.sempods.client.wire.PodWriteResult
import java.net.URI

/**
 * The outcome of reading a `tools/call` against the catalog: an executable call, or the reason it
 * will never reach a pod.
 *
 * Two phases rather than one method, because the two surfaces need the split for different reasons.
 * The hosted service parses once and executes per pod, so a bad argument has to be one tool-level
 * error and not the same message repeated in every fan-out entry. The pod-immanent surface executes
 * once, and still wants the refusal before it opens a socket.
 */
sealed interface PodToolPlan {

  /**
   * A validated call, ready to run against one pod.
   *
   * [execute] blocks and **lets pod failures propagate**. That is not an omission: on the hosted
   * side this runs inside `podIo` on a virtual thread, where a cancelled coroutine arrives as an
   * ordinary socket failure (the call slot closed the socket) rather than as a
   * `CancellationException`. An executor that turned exceptions into result envelopes here would
   * answer a cancelled request with a well-formed "the pod failed" instead of tearing it down.
   * Classifying a failure is the caller's, one frame further out, where cancellation is still
   * visible as itself.
   */
  class Call internal constructor(private val run: (URI, String?) -> Any?) : PodToolPlan {

    /**
     * Runs the call against [podBaseUrl] with [accessToken] as the bearer, and returns the tool's
     * result payload — what a single pod answers, with no envelope around it.
     *
     * [accessToken] is nullable because anonymous is a supported mode: the pod-immanent surface
     * serves public contexts without one.
     */
    fun execute(podBaseUrl: URI, accessToken: String?): Any? = run(podBaseUrl, accessToken)
  }

  /** The catalog does not carry that tool name — or it is a surface's own, like `list_pods`. */
  data class UnknownTool(val message: String) : PodToolPlan

  /** The arguments do not satisfy the advertised schema, or a value cannot be used as given. */
  data class InvalidArguments(val message: String) : PodToolPlan
}

/**
 * The thirteen tools against **one** pod: argument parsing, the call into [PodWireClient], and the
 * shape of what comes back.
 *
 * This is the half of the MCP surface that is the same wherever it runs. The hosted service adds
 * fan-out, `targets` / `target`, the per-pod envelope and `list_pods`; the pod-immanent surface adds
 * its own route, discovery and `authorize`. Neither of those is here, and neither is anything
 * coroutine-shaped — [PodWireClient] blocks, so this blocks, and a `suspend` consumer bridges at its
 * own edge.
 *
 * `authorize` is deliberately absent for the same reason it is absent from [ToolCatalog]: it reads
 * the claims of the incoming token and means a grant upgrade on one surface and a pod reconnect on
 * the other.
 */
class PodToolExecutor(private val catalog: ToolCatalog, private val wire: PodWireClient) {

  init {
    // Drift guard in the direction that would silently break routing: a tool this class maps has to
    // exist in the catalog it validates against, or the call would be refused as unknown after the
    // surface already routed it here. The other direction — a catalog tool with no branch below —
    // is `list_pods` by design, and is what `PodToolExecutorTest` checks.
    val missing = TOOL_NAMES - (catalog.readToolNames + catalog.writeToolNames)
    require(missing.isEmpty()) { "${catalog.variant} catalog does not carry: ${missing.sorted()}" }
  }

  /**
   * Validates [arguments] against the advertised schema and parses them, without calling anything.
   *
   * Refusal messages are the caller's to render; this decides only *that* a call is refused and
   * why.
   */
  fun plan(toolName: String, arguments: JsonNode?): PodToolPlan {
    if (toolName !in TOOL_NAMES) return PodToolPlan.UnknownTool("Unknown tool: $toolName")
    catalog.validate(toolName, arguments)?.let { return PodToolPlan.InvalidArguments(it) }

    // One IRI rule for every tool, read and write. It used to be a write-only loop, and the reads
    // paid for that: a malformed `resource_iri` blew up on `URI` parsing inside the fan-out and came
    // back as "the pod failed", which it had not. The sharper half is the filters — a `context_iri`
    // that cannot be an IRI is dropped, and a dropped filter fails OPEN: `find` then searches every
    // readable context instead of the one the caller named. Same reasoning `validate` gives for
    // refusing blank array elements. Prefixed forms stay legal (`schema:Person` is absolute).
    iriRefusal(arguments)?.let { return PodToolPlan.InvalidArguments(it) }

    // Preconditions are normalized up front, before anything is called. A client may echo an ETag
    // back without quotes (`v1` instead of `"v1"`); forwarded verbatim the pod's header parser
    // rejects it and then proceeds UNCONDITIONALLY — silently losing the lost-update protection the
    // caller asked for. Quote a bare token, pass `*` through, refuse what cannot be made a tag.
    val ifMatch = rawText(arguments, "if_match")?.let {
      normalizeEtag(it) ?: return PodToolPlan.InvalidArguments(
        "if_match is not a valid ETag (use the `etag` from a read, or \"*\"): $it",
      )
    }
    val ifNoneMatch = rawText(arguments, "if_none_match")?.let {
      normalizeEtag(it) ?: return PodToolPlan.InvalidArguments("if_none_match must be \"*\" or a valid ETag: $it")
    }

    val contextIris = ToolArguments.stringList(arguments, "context_iri").map(URI::create)

    return when (toolName) {

      // --- reads: `context_iri` is a downscope the pod intersects with what the bearer may read ---

      "list_contexts" -> call { pod, token -> wire.listContexts(pod, token) }

      "sparql_select", "sparql_graph" -> {
        val query = ToolArguments.string(arguments, "query")
          ?: return PodToolPlan.InvalidArguments("missing required argument: query")
        // Read-only enforcement is the pod's job: it parses the query (`validateReadOnly`) and
        // rejects updates / SERVICE with a 400. Deliberately no keyword pre-screen here — it
        // false-positives on keywords inside literals, IRIs and prefix names (a FILTER on the
        // literal "Create", a `…/service#` prefix), rejecting valid reads.
        if (toolName == "sparql_graph") call { pod, token -> wire.sparqlGraph(pod, query, contextIris, token) }
        else call { pod, token -> wire.sparqlSelect(pod, query, contextIris, token) }
      }

      "find" -> {
        val text = ToolArguments.string(arguments, "text")?.takeIf { it.isNotBlank() }
          ?: return PodToolPlan.InvalidArguments("missing or blank required argument: text")
        val types = ToolArguments.stringList(arguments, "type").map(URI::create)
        val includeContexts = ToolArguments.flag(arguments, "include_contexts")
        // Accept any integral (validation allows int or long) and clamp to the advertised 1..100, so
        // an out-of-range or long value can neither reach the pod raw nor silently fall to default.
        val limit = arguments?.get("limit")?.takeIf { it.isIntegralNumber }?.asLong()?.coerceIn(1L, 100L)?.toInt()
        call { pod, token -> wire.find(pod, text, types, contextIris, includeContexts, limit, token) }
      }

      "get_resource" -> {
        val resourceIri = ToolArguments.string(arguments, "resource_iri")
          ?: return PodToolPlan.InvalidArguments("missing required argument: resource_iri")
        val includeContexts = ToolArguments.flag(arguments, "include_contexts")
        call { pod, token ->
          val resource = URI.create(resourceIri)
          val r = wire.getResource(pod, resource, contextIris, includeContexts, token)
          // The `etag` this tool returns is the **write-precondition** tag, which is what the
          // description promises and what `update_resource`/`delete_resource`'s `if_match` accepts.
          //
          // HTTP does not hand it over under `include_contexts`: an `ETag` identifies a
          // representation, so the named-graph GET answers with that representation's validator —
          // the pod appends a `-contexts` marker — and sending it back as `if_match` is a
          // precondition failure on an unchanged resource. Read it from the canonical
          // representation instead, which costs a second GET on this one path. Deriving it by
          // stripping a suffix would put the pod's tag format into this module, where it cannot be
          // checked and would go stale in silence.
          val etag = if (includeContexts) wire.getResource(pod, resource, contextIris, false, token).etag else r.etag
          linkedMapOf("resource_iri" to resourceIri, "etag" to etag, "jsonld" to r.jsonld)
        }
      }

      "get_property_values" -> {
        val subjectIri = ToolArguments.string(arguments, "subject_iri")
          ?: return PodToolPlan.InvalidArguments("missing required argument: subject_iri")
        val predicateIri = ToolArguments.string(arguments, "predicate_iri")
          ?: return PodToolPlan.InvalidArguments("missing required argument: predicate_iri")
        call { pod, token ->
          // A slot with nothing in it has no representation, so the route answers 404 — and answers
          // the same 404 for a context the caller may not read, which is deliberate and must stay
          // indistinguishable. For this tool that is not a failure: it was asked what the values are,
          // and "none" is the answer. Reported as an error it would read to a model as a broken call
          // and get retried.
          //
          // The narrow condition is what makes catching safe here at all. The rule this class
          // otherwise follows — never swallow a pod failure — exists because a cancelled request
          // arrives as an ordinary socket error; those carry no status, so `statusCode == 404` cannot
          // be one. `get_resource` keeps the 404: a resource either exists or does not, and saying so
          // is the answer there.
          val s = try {
            wire.getPropertyValues(pod, URI.create(subjectIri), URI.create(predicateIri), contextIris, token)
          } catch (e: SempodsClientException) {
            if (e.statusCode != 404) throw e
            // A fresh node rather than a shared constant: `ArrayNode` is mutable, and a caller that
            // serialises it is not the only thing that could reach it.
            PodSlot(etag = null, values = JsonNodeFactory.instance.arrayNode())
          }
          val result = linkedMapOf<String, Any?>(
            "subject_iri" to subjectIri, "predicate_iri" to predicateIri, "values" to s.values,
          )
          // Omitted rather than null, the way [written] omits a write's absent tag. A slot has a
          // validator only for a single-context read that returned something — the pod withholds
          // one for a union read (no single validator exists) and for an empty or unreadable slot
          // (which is what keeps the subject's global change state from leaking to a caller who
          // may not read that context). `"etag": null` invites a model to send the string "null"
          // back as `if_match`; an absent field says the same thing and cannot be misread.
          s.etag?.let { result["etag"] = it }
          result
        }
      }

      // --- writes: one pod, one context, never a fan-out ---
      //
      // Who may write where is entirely the pod's decision — it resolves `context_iri` against its
      // own registry and enforces the `<context_iri>#write` scope, answering 404 or 403. What is
      // checked here is argument *shape* and nothing else. There used to be a reserved-area guard
      // refusing anything under a pod's `_system` / `.well-known`; it was neither necessary (the
      // pod's registry already answers) nor correct (it refused resource subjects the pod allows on
      // purpose, and carried a copy of the context namespace that went stale when contexts moved to
      // `_system/contexts/`). Do not reintroduce it.

      else -> {
        val contextIri = ToolArguments.string(arguments, "context_iri")
          ?: return PodToolPlan.InvalidArguments("missing required argument: context_iri")
        val context = URI.create(contextIri)

        when (toolName) {
          "create_resource", "update_resource", "delete_resource" -> {
            val resourceIri = ToolArguments.string(arguments, "resource_iri")
              ?: return PodToolPlan.InvalidArguments("missing required argument: resource_iri")
            val resource = URI.create(resourceIri)
            val ids = linkedMapOf<String, Any?>("context_iri" to contextIri, "resource_iri" to resourceIri)
            when (toolName) {
              "create_resource" -> {
                val jsonld = ToolArguments.obj(arguments, "jsonld")
                  ?: return PodToolPlan.InvalidArguments("argument 'jsonld' must be a JSON-LD object")
                // `@id` is set to the resource the caller addressed, overriding whatever the body
                // carried. The route already names the resource, so the field is redundant to the
                // pod — but *omitting* it is not: JSON-LD expansion turns a body without `@id` into
                // a blank node, and the write path refuses blank nodes with a 400 that talks about
                // RDF rather than about the field the caller forgot. That is the single most common
                // shape a model produces, so the tool closes it here rather than teaching every
                // client to. A body naming a *different* `@id` is a caller contradicting its own
                // `resource_iri`; the argument wins, because it is what the result echoes back.
                val body = (jsonld.deepCopy() as ObjectNode).put("@id", resourceIri)
                call { pod, token ->
                  written(ids, wire.createResource(pod, context, resource, body, ifNoneMatch, token))
                }
              }
              "update_resource" -> {
                val patch = ToolArguments.obj(arguments, "jsonld_patch")
                  ?: return PodToolPlan.InvalidArguments("argument 'jsonld_patch' must be a JSON object")
                call { pod, token ->
                  written(ids, wire.updateResource(pod, context, resource, patch, ifMatch, token))
                }
              }
              else -> call { pod, token -> written(ids, wire.deleteResource(pod, context, resource, ifMatch, token)) }
            }
          }

          else -> {
            val subjectIri = ToolArguments.string(arguments, "subject_iri")
              ?: return PodToolPlan.InvalidArguments("missing required argument: subject_iri")
            val predicateIri = ToolArguments.string(arguments, "predicate_iri")
              ?: return PodToolPlan.InvalidArguments("missing required argument: predicate_iri")
            val subject = URI.create(subjectIri)
            val predicate = URI.create(predicateIri)
            val ids = linkedMapOf<String, Any?>(
              "context_iri" to contextIri, "subject_iri" to subjectIri, "predicate_iri" to predicateIri,
            )
            when (toolName) {
              "add_property_value" -> {
                val value = ToolArguments.obj(arguments, "value")
                  ?: return PodToolPlan.InvalidArguments("argument 'value' must be a JSON-LD value object")
                call { pod, token ->
                  written(ids, wire.addPropertyValue(pod, context, subject, predicate, value, ifMatch, token))
                }
              }
              "set_property_values" -> {
                val values = ToolArguments.arr(arguments, "values")
                  ?: return PodToolPlan.InvalidArguments("argument 'values' must be a JSON array")
                call { pod, token ->
                  written(ids, wire.setPropertyValues(pod, context, subject, predicate, values, ifMatch, token))
                }
              }
              "remove_property_value" -> {
                val targetIri = ToolArguments.string(arguments, "target_iri")
                  ?: return PodToolPlan.InvalidArguments("missing required argument: target_iri")
                // No precondition: removing one edge is idempotent, and the catalog does not offer
                // `if_match` on this tool.
                val edgeIds = LinkedHashMap(ids).apply { put("target_iri", targetIri) }
                call { pod, token ->
                  written(edgeIds, wire.removePropertyValue(pod, context, subject, predicate, URI.create(targetIri), token))
                }
              }
              else -> call { pod, token ->
                written(ids, wire.clearPropertyValues(pod, context, subject, predicate, ifMatch, token))
              }
            }
          }
        }
      }
    }
  }

  private fun call(run: (URI, String?) -> Any?): PodToolPlan.Call = PodToolPlan.Call(run)

  /**
   * The write result: the ids the caller addressed, echoed back, then what the pod answered.
   *
   * Built fresh on every execution rather than mutated in place — key order is the JSON order, and
   * a plan may be executed more than once.
   */
  private fun written(ids: Map<String, Any?>, r: PodWriteResult): Map<String, Any?> {
    val result = LinkedHashMap(ids)
    // Lifted out of the body rather than left nested under `response`, because it is what the tool
    // descriptions promise by name ("the second call returns `outcome=already_present`") and because
    // it is the answer to the question an idempotent write leaves open. The three slot mutations are
    // the routes that carry one; everything else has nothing to lift.
    r.body?.path("outcome")?.takeIf { it.isTextual }?.let { result["outcome"] = it.asText() }
    result["status"] = r.status
    r.etag?.let { result["etag"] = it }
    // The whole body still travels: `outcome` is a summary, and a route that grows a second field
    // must not need this class edited before a caller can see it.
    r.body?.let { result["response"] = it }
    return result
  }

  /** The first IRI argument that is not an absolute IRI, as a refusal message — or null. */
  private fun iriRefusal(arguments: JsonNode?): String? {
    for (field in IRI_FIELDS) {
      val node = arguments?.get(field) ?: continue
      val values = if (node.isArray) ToolArguments.stringList(arguments, field) else listOfNotNull(ToolArguments.string(arguments, field))
      values.firstOrNull { !isAbsoluteIri(it) }?.let { return "$field must be an absolute IRI: $it" }
    }
    return null
  }

  private fun isAbsoluteIri(value: String): Boolean =
    runCatching { URI.create(value).isAbsolute }.getOrDefault(false)

  /** An argument read verbatim — no trim, no blank-drop, because [normalizeEtag] judges both. */
  private fun rawText(arguments: JsonNode?, field: String): String? =
    arguments?.get(field)?.takeIf { it.isTextual }?.asText()

  /**
   * Coerce a client-supplied precondition into a valid HTTP entity-tag, or null if it cannot be one.
   * `*` passes through; an already-quoted (optionally weak `W/"…"`) tag is kept; a bare token (`v1`)
   * is quoted (`"v1"`). An empty value, or one whose opaque part contains a `"`, is refused.
   */
  private fun normalizeEtag(raw: String): String? {
    val v = raw.trim()
    if (v.isEmpty()) return null
    if (v == "*") return "*"
    val weak = v.startsWith("W/")
    val core = (if (weak) v.substring(2) else v).trim()
    val inner = if (core.length >= 2 && core.startsWith("\"") && core.endsWith("\"")) core.substring(1, core.length - 1) else core
    if (inner.isEmpty() || inner.contains('"')) return null
    return (if (weak) "W/" else "") + "\"" + inner + "\""
  }

  companion object {

    /**
     * The tools this executor maps — the thirteen pod operations [PodWireClient] carries.
     *
     * Not derived from the catalog: `list_pods` is in the `MULTI_POD` catalog and is *not* one of
     * these, because it is answered from the hosted service's own registry and makes no pod call.
     * Deriving would have to subtract it by name, which says the same thing less directly.
     */
    val TOOL_NAMES: Set<String> = setOf(
      "list_contexts", "sparql_select", "sparql_graph", "find", "get_resource", "get_property_values",
      "create_resource", "update_resource", "delete_resource",
      "add_property_value", "set_property_values", "remove_property_value", "clear_property_values",
    )

    /**
     * Every IRI-typed argument in the catalog, checked wherever it appears.
     *
     * One list for both variants and for reads and writes: `target` is simply absent on
     * `SINGLE_POD`, and `context_iri` is a string on the writes and an array on the reads, which is
     * why the check reads the node's shape rather than a per-tool table.
     */
    private val IRI_FIELDS = listOf(
      "target", "context_iri", "resource_iri", "subject_iri", "predicate_iri", "target_iri", "type",
    )
  }
}
