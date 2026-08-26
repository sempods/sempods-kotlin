package org.sempods.mcp.core

import com.fasterxml.jackson.databind.JsonNode

/** Which MCP surface a catalog is built for. */
enum class ToolVariant {

  /**
   * The pod-immanent MCP in `sempods-server`: one pod, addressed by the URL the client is talking
   * to. No pod selector on any tool.
   */
  SINGLE_POD,

  /**
   * The hosted service in `sempods-mcp`: several connected pods behind one endpoint. Read tools
   * gain an optional `targets` filter, write tools a required single `target`, and `list_pods`
   * exists to name them.
   */
  MULTI_POD,
}

/**
 * The tool surface both MCP implementations advertise, in one place.
 *
 * `tools/list` builds from here and `tools/call` dispatches the names defined here; the
 * human-readable contract lives in `sempods-mcp/docs/tool-contract.md`. Before this module the same
 * fourteen tools were declared twice with the same names, the same argument shapes and separately
 * worded descriptions — the divergence risk `docs/concepts/hosted-mcp.md` names under "Toolset
 * divergence". What differs between the surfaces is now a [ToolVariant] rather than a second file.
 *
 * Instances are memoized per variant: [McpEndpoint-style] endpoints are constructed per request on
 * the JAX-RS side, so a catalog built in a constructor would be rebuilt for every call.
 */
class ToolCatalog private constructor(val variant: ToolVariant) {

  /** Tool names, **derived** from the specs so routing and the catalog cannot drift apart. */
  val readToolNames: Set<String> by lazy { readSpecs.map { it.name }.toSet() }
  val writeToolNames: Set<String> by lazy { writeSpecs.map { it.name }.toSet() }

  fun readTools(): List<Tool> = readSpecs

  fun writeTools(): List<Tool> = writeSpecs

  /** The declared schema of one tool, or null when this catalog does not carry that name. */
  fun schemaFor(toolName: String): ToolInputSchema? = specsByName[toolName]?.inputSchema

  private val readSpecs: List<Tool> by lazy { buildReadSpecs() }
  private val writeSpecs: List<Tool> by lazy { buildWriteSpecs() }
  private val specsByName: Map<String, Tool> by lazy {
    // Routing checks read names before write names, while this map resolves both (write-wins on a
    // collision) — an overlapping name would validate against one schema and dispatch to the other.
    // Fail loud when the catalog is first built rather than diverge silently.
    val overlap = readToolNames intersect writeToolNames
    require(overlap.isEmpty()) { "read and write tool names must be disjoint; overlap: $overlap" }
    (readSpecs + writeSpecs).associateBy { it.name }
  }

  /**
   * Server-side enforcement of a tool's advertised `inputSchema`.
   *
   * The schema declares `additionalProperties: false` and per-argument types, but a hand-rolled
   * JSON-RPC layer has to actually enforce them — otherwise an unknown or mistyped argument (say
   * `context_iri` as a string, `limit` as a string) is silently dropped, which can widen a read
   * beyond what the caller intended. Returns a human-readable message, or null when valid.
   */
  // A name this catalog does not carry is refused rather than waved through. It used to return
  // null, which was safe only because every caller happened to reject unknown names in its own
  // `else` branch first — fail-open in a guard that reads like a gate. The wording is the one both
  // surfaces already answer an unknown tool with, so there is one string for one condition.
  fun validate(toolName: String, arguments: JsonNode?): String? {
    val schema = specsByName[toolName]?.inputSchema ?: return "Unknown tool: $toolName"

    if (arguments != null && !arguments.isNull && !arguments.isObject) return "arguments must be a JSON object"

    if (arguments != null && arguments.isObject) {
      for (field in arguments.fieldNames()) {
        val declared = schema.properties[field] ?: return "unknown argument: '$field'"
        val node = arguments.get(field)
        // Reject an explicit `null` for a declared argument: skipping it would let a filter-like
        // argument (`targets` / `context_iri` / `type`) passed as null fall through downstream to
        // "no filter", widening the read. Callers omit what they do not want, they do not pass null.
        if (node == null || node.isNull) return "argument '$field' must not be null (omit it instead)"
        if (!matchesType(node, declared.type)) return "argument '$field' must be of type ${declared.type}"
        // For a typed array, enforce the declared element type too. Two reasons, and the first is
        // the sharper one: `targets:[5]` would pass the array check, get silently dropped to an
        // empty list downstream, and the restriction would fail open to "all pods". The second is
        // that a declared `items` the validator ignores is a promise the catalog does not keep —
        // `values:[1]` would reach the pod and come back as a 400 classified as a pod failure
        // rather than as the invalid argument it is.
        val itemType = declared.items?.type
        if (declared.type == "array" && itemType != null) {
          for (element in node) {
            if (!matchesType(element, itemType)) {
              return "argument '$field' must be an array of ${itemType}s"
            }
            // Blank strings are the fail-open case again: an empty filter entry is not a filter.
            if (itemType == "string" && element.asText().isBlank()) {
              return "argument '$field' must be an array of non-empty strings"
            }
          }
        }
      }
    }
    for (req in schema.required) {
      val node = arguments?.get(req)
      if (node == null || node.isNull) return "missing required argument: $req"
    }
    return null
  }

  private fun matchesType(node: JsonNode, type: String): Boolean = when (type) {
    "string" -> node.isTextual
    "array" -> node.isArray
    "boolean" -> node.isBoolean
    "integer" -> node.isInt || node.isLong
    "number" -> node.isNumber
    "object" -> node.isObject
    else -> true
  }

  // ---------------------------------------------------------------------------------------------
  // Variant vocabulary
  //
  // One text, with the surface's differences named. The rule that keeps this from becoming two
  // catalogs again: a fragment used by two or more tools gets a name here; one used by a single
  // tool stays an inline `pick` at the end of that tool's text. Nested or mid-sentence branches are
  // not allowed — if a sentence cannot be written once for both surfaces, it is a real difference
  // and belongs in its own fragment.
  //
  // Seven named fragments today. Materially more would mean the variant axis stopped paying for
  // itself and the split deserves rethinking rather than another constant.
  // ---------------------------------------------------------------------------------------------

  private fun pick(singlePod: String, multiPod: String) =
    if (variant == ToolVariant.SINGLE_POD) singlePod else multiPod

  /** The one phrase the whole variant axis exists for: what a single call addresses. */
  private val scope = pick("this pod", "each of your connected pods")

  /** Possessive form of [scope] — "the contexts <scopeOwner> can see". */
  private val scopeOwner = pick("this session", "you")

  /** Suffix on a result count or shape that is produced once per pod on a fan-out. */
  private val perPod = pick("", " per pod")

  /** Where a write lands. Reads fan out; writes never do. */
  private val oneTarget = pick("", " of ONE connected pod (never fans out)")

  /**
   * `SINGLE_POD` only. `authorize` means different things per surface — on the pod it upgrades the
   * grant for that pod, on the hosted service it re-opens consent to connect further pods — so this
   * pointer is absent rather than reworded on the other side.
   */
  private val authorizeHint = pick(
    " If you are not authorized to write here, call the `authorize` tool first to start the OAuth flow.",
    "",
  )

  /**
   * The `target` line a worked example has to carry on `MULTI_POD`, where it is required.
   *
   * The examples say "copy this shape", so an example missing a required argument produces a call
   * the validator refuses before it ever reaches a pod. Indented and comma-terminated to sit as the
   * first line inside the example object, which is also where `target` sits in the schema.
   */
  private val exampleTarget = pick(
    "",
    "\n    \"target\": \"https://sempods.org/alice\",",
  )

  /** Optional pod filter on a fan-out read. `MULTI_POD` only. */
  private val targetsProp = stringArray(
    "Optional array of connected pod base URLs to restrict this call to (a subset of your connected " +
      "pods; from list_pods). Omit to query all your connected pods.",
  )

  /** The required single-pod target of a write. `MULTI_POD` only — writes never fan out. */
  private val targetProp = prop(
    "string",
    "Required. The single connected pod base URL to write to (from list_pods). Writes never fan out: " +
      "exactly one pod, one context.",
  )

  /**
   * A read tool. On `MULTI_POD` the `targets` filter is **appended**, which is where it sits on the
   * wire today.
   *
   * [fansOut] is false for the one read tool that answers from this service's own state rather than
   * from the pods — `list_pods` names them, so filtering it by pod would be circular.
   */
  private fun readTool(
    name: String,
    description: String,
    properties: Map<String, PropertySchema> = emptyMap(),
    required: List<String> = emptyList(),
    fansOut: Boolean = true,
  ): Tool = Tool(
    name = name,
    description = description,
    inputSchema = ToolInputSchema(
      type = "object",
      properties = if (fansOut && variant == ToolVariant.MULTI_POD) properties + ("targets" to targetsProp) else properties,
      required = required,
      additionalProperties = false,
    ),
  )

  /**
   * A write tool. On `MULTI_POD` the required `target` is **prepended**, both in the properties and
   * in `required` — again the order the wire already has.
   */
  private fun writeTool(
    name: String,
    description: String,
    properties: Map<String, PropertySchema>,
    required: List<String>,
  ): Tool = Tool(
    name = name,
    description = description,
    inputSchema = ToolInputSchema(
      type = "object",
      properties = if (variant == ToolVariant.MULTI_POD) mapOf("target" to targetProp) + properties else properties,
      required = if (variant == ToolVariant.MULTI_POD) listOf("target") + required else required,
      additionalProperties = false,
    ),
  )

  private fun buildReadSpecs(): List<Tool> = buildList {
    if (variant == ToolVariant.MULTI_POD) {
      add(
        readTool(
          "list_pods",
          "List the pods you have connected to this service (for the active profile), with each pod's " +
            "OAuth issuer and the access token's feature `scopes` (e.g. `public-read`). NOTE: `scopes` " +
            "is NOT the per-context access — a pod grants read/write on specific contexts as grants that " +
            "do not appear here; call `list_contexts` for the authoritative read/write view. Each entry " +
            "also carries `pod_subject` (the WebID the pod authorized you as) and `foreign_identity`: " +
            "when true, you act on that pod as `pod_subject`, NOT your sempods identity — attribute " +
            "reads/writes there accordingly, and `similar_to` names your sempods WebID that `pod_subject` " +
            "likely denotes the same person as (a weak correlation hint, not an asserted identity). " +
            "Use the returned pod base URLs as `targets` for the other " +
            "tools. Makes no call to the pods themselves. Takes no arguments.",
          fansOut = false,
        ),
      )
    }

    add(
      readTool(
        "list_contexts",
        "Return the contexts (named graphs) $scopeOwner can see, with the permission level on each. " +
          "Call this FIRST before write tools — a `context_iri` returned here is the only valid value " +
          "for ANY write tool: `create_resource` / `update_resource` / `delete_resource` AND the " +
          "property-value tools `add_property_value` / `set_property_values` / `remove_property_value` / " +
          "`clear_property_values`. Results are grouped$perPod." +
          pick(
            " Works for both anonymous (public contexts only) and authenticated callers. If " +
              "`writable_contexts` is empty and the user wants to write, call the `authorize` tool first " +
              "to start the OAuth flow. Takes no arguments.",
            " Optionally restrict the fan-out to a subset of pods with `targets`.",
          ),
      ),
    )

    add(
      readTool(
        "sparql_select",
        "Execute a read-only SPARQL SELECT or ASK query against $scope. Returns SPARQL-Results-JSON" +
          "$perPod (rows with named bindings, see https://www.w3.org/TR/sparql11-results-json/). Best " +
          "for discovery: list distinct types, list predicates used by a resource, count things. Every " +
          "call is sandboxed to the contexts you may read; by default the query runs over all of them, " +
          "and `context_iri` restricts it to specific named graphs (or use GRAPH/FROM inside the query)." +
          pick(
            "",
            " Provenance is per pod, not per context. NOTE: the query runs independently on each pod, " +
              "so ORDER BY and LIMIT apply PER POD, not globally — for a global top-N or ranking across " +
              "pods, fetch and re-rank the per-pod results yourself.",
          ),
        properties = mapOf(
          "query" to prop("string", "The SPARQL SELECT or ASK query. Write operations and SERVICE are rejected."),
          "context_iri" to stringArray(
            "Optional array of context IRIs to restrict the query to (applied as the SPARQL-protocol " +
              "dataset). Omit to query across every readable context of $scope. Unknown or unreadable " +
              "contexts are dropped; if none remain readable, the query returns no results — it does not " +
              "fall back to the whole pod.",
          ),
        ),
        required = listOf("query"),
      ),
    )

    add(
      readTool(
        "sparql_graph",
        "Execute a read-only SPARQL CONSTRUCT or DESCRIBE query against $scope. Returns JSON-LD" +
          "$perPod. Best for fetching all properties of a known resource without guessing predicate " +
          "names. Every call is sandboxed to the contexts you may read; by default the query runs over " +
          "all of them, and `context_iri` restricts it to specific named graphs (or use GRAPH/FROM " +
          "inside the query)." +
          pick(
            "",
            " NOTE: the query runs independently on each pod, so any LIMIT applies PER POD, not globally.",
          ),
        properties = mapOf(
          "query" to prop("string", "The SPARQL CONSTRUCT or DESCRIBE query. Write operations and SERVICE are rejected."),
          "context_iri" to stringArray(
            "Optional array of context IRIs to restrict the query to (applied as the SPARQL-protocol " +
              "dataset). Omit to query across every readable context of $scope. Unknown or unreadable " +
              "contexts are dropped; if none remain readable, the result graph is empty — it does not " +
              "fall back to the whole pod.",
          ),
        ),
        required = listOf("query"),
      ),
    )

    add(
      readTool(
        "find",
        "Search $scope for resources by text — the semantic entry to graph retrieval. Returns a " +
          "context-sandboxed RDF subgraph (matching resources plus a type/label/name expansion) as " +
          "JSON-LD$perPod; feed the IRIs into `get_resource` / `sparql_graph` to go deeper. Prefer this " +
          "over hand-written SPARQL when you don't know the exact predicates.",
        properties = mapOf(
          "text" to prop("string", "Required search text. Whitespace-split, case-insensitive; a resource matches when one of its literals contains all tokens. Whitespace-only is rejected."),
          "type" to stringArray("Optional array of rdf:type IRIs (OR-combined): only resources of one of these types are returned. Omit for an unconstrained text search."),
          "context_iri" to stringArray(
            "Optional array of context IRIs to restrict the search to (a downscope within the contexts " +
              "you may read; use values from `list_contexts`). Omit to search across every readable " +
              "context of $scope. Unknown or unreadable contexts are silently ignored.",
          ),
          "include_contexts" to prop(
            "boolean",
            "Optional: when true, return the named-graph form (matching and expansion statements grouped " +
              "by the context they came from) instead of the merged flat graph. Use it to see which " +
              "context each hit lives in — e.g. to tell a public result apart from a private one.",
          ),
          "limit" to prop("integer", "Optional max number of matching resources$perPod. Clamped to 1..100, default 10."),
        ),
        required = listOf("text"),
      ),
    )

    add(
      readTool(
        "get_resource",
        "Fetch the whole-resource view of a KNOWN resource_iri as canonical JSON-LD from $scope, " +
          "together with its `etag`. Use this before `update_resource` / `delete_resource` to read the " +
          "current state AND obtain the `etag` to pass back as `if_match` for a safe read-modify-write " +
          "(lost-update protection). `resource_iri` may be local or external (`did:`, `urn:`, foreign " +
          "`https://…`).\n\n" +
          "Returns `{ \"resource_iri\": \"…\", \"etag\": \"…\", \"jsonld\": <canonical JSON-LD> }`" +
          "$perPod. Pass the `etag` verbatim to `update_resource.if_match`. Prefer this over " +
          "hand-written SPARQL when you already know the resource IRI. Where the resource has no " +
          "visible statements, the answer is a not-visible error rather than an empty document.",
        properties = mapOf(
          "resource_iri" to prop("string", "Absolute IRI of the resource to read (local or external)."),
          "context_iri" to stringArray(
            "Optional array of context IRIs to downscope the read to. Omit to union across every " +
              "readable context of $scope. Unknown or unreadable contexts are silently ignored; if none " +
              "remain, the answer is a not-visible error.",
          ),
          "include_contexts" to prop(
            "boolean",
            "Optional: when true, return the named-graph form (statements grouped by context) instead of " +
              "the merged canonical document. The returned `etag` is the same write-precondition tag " +
              "either way — it does not describe the named-graph representation — so it stays usable for " +
              "`if_match`.",
          ),
        ),
        required = listOf("resource_iri"),
      ),
    )

    add(
      readTool(
        "get_property_values",
        "Read all values of one slot `(subject_iri, predicate_iri)` as a JSON-LD value array from " +
          "$scope. With a single readable context, also returns a slot `etag` to pass as `if_match` to " +
          "`set_property_values` / `clear_property_values` for a safe read-modify-write. `subject_iri` " +
          "may be local or external. Returns " +
          "`{ \"subject_iri\", \"predicate_iri\", \"values\": [...], \"etag\"? }`$perPod.",
        properties = mapOf(
          "subject_iri" to prop("string", "Absolute IRI of the subject (local or external)."),
          "predicate_iri" to prop("string", "Absolute IRI of the predicate (e.g. https://schema.org/children)."),
          "context_iri" to stringArray("Optional array of context IRIs to downscope the slot read to. A slot `etag` is returned only when exactly one readable context remains and values are visible."),
        ),
        required = listOf("subject_iri", "predicate_iri"),
      ),
    )
  }

  /**
   * The write / property-mutation tools. Unlike the reads these **never fan out**: on `MULTI_POD`
   * each takes a required single `target` (one connected pod base URL), and on both variants a
   * required single `context_iri` (a string, not an array). Who may write where is entirely the
   * pod's decision — it resolves the context against its own registry and enforces the
   * `<context_iri>#write` scope. A surface in front of it validates argument *shape* and forwards.
   */
  private fun buildWriteSpecs(): List<Tool> = listOf(
    writeTool(
      "create_resource",
      "Create or replace a whole resource inside ONE context$oneTarget. Requires the " +
        "`<context_iri>#write` (or `#manage`) scope on the target context.$authorizeHint\n\n" +
        "STRICT CONTRACT: exactly the declared top-level arguments. Any other field (observed " +
        "hallucinations: `statements`, `triples`, `quads`) is rejected by the schema.\n\n" +
        "The `jsonld` object is RDF-serialised via JSON-LD 1.1 expansion. That means:\n" +
        "  - Every predicate MUST resolve to an absolute IRI. Bare keys like \"sender\" or \"content\" " +
        "expand to NOTHING and you get HTTP 400 \"parsed to 0 RDF statements\".\n" +
        "  - `@type` likewise MUST be an absolute IRI (\"Message\" expands to nothing; " +
        "\"https://schema.org/Message\" works).\n" +
        "  - Either write every predicate as an absolute IRI, or declare shorthand in \"@context\".\n\n" +
        "WORKING EXAMPLE (copy this shape, swap IRIs/values):\n\n" +
        "  {" + exampleTarget + "\n" +
        "    \"context_iri\": \"https://sempods.org/alice/ai-playground\",\n" +
        "    \"resource_iri\": \"https://sempods.org/alice/ai-playground/message/chatgpt-1\",\n" +
        "    \"jsonld\": {\n" +
        "      \"@context\": {\n" +
        "        \"schema\": \"https://schema.org/\",\n" +
        "        \"sender\": \"schema:sender\",\n" +
        "        \"text\":   \"schema:text\",\n" +
        "        \"intent\": \"https://sempods.org/vocab/intent\"\n" +
        "      },\n" +
        "      \"@id\":    \"https://sempods.org/alice/ai-playground/message/chatgpt-1\",\n" +
        "      \"@type\":  \"schema:Message\",\n" +
        "      \"sender\": \"ChatGPT\",\n" +
        "      \"intent\": \"agent_collaboration\",\n" +
        "      \"text\":   \"Hallo Opus, …\"\n" +
        "    }\n" +
        "  }\n\n" +
        "Confirm the target context_iri with the user unless they named it explicitly. The " +
        "`resource_iri` must equal `jsonld[\"@id\"]`. It may be ANY absolute IRI — a resource in the " +
        "pod or an external URI (`did:`, `urn:`, foreign `https://…`) — so you can enrich an external " +
        "identity (say a `foaf:`/`schema:Person`) with pod-local statements. Where a statement is " +
        "stored is decided by `context_iri`, not by what it is about. Pass `if_none_match: \"*\"` for " +
        "create-or-fail; omit it for upsert.",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the writable context (graph) the resource belongs to. Use one of the writable_contexts from `list_contexts`."),
        "resource_iri" to prop("string", "Absolute IRI of the resource — local or external (did:, urn:, foreign https://…). Must equal `jsonld[\"@id\"]`."),
        "jsonld" to prop("object", "A single JSON-LD object. Predicates (including @type) must be absolute IRIs or mapped via @context; bare keys expand to nothing (HTTP 400). See the tool description for a working example."),
        "if_none_match" to prop("string", "Optional. Pass \"*\" for create-or-fail: the call fails with a precondition error if the resource already exists. Omit for upsert (the default — replaces an existing resource in the context)."),
      ),
      required = listOf("context_iri", "resource_iri", "jsonld"),
    ),
    writeTool(
      "update_resource",
      "Apply a strict RFC 7396 JSON merge-patch to an existing resource's CANONICAL JSON-LD shape " +
        "inside ONE context$oneTarget. STRICT CONTRACT: exactly the declared top-level arguments — " +
        "any other field is rejected.\n\n" +
        "CANONICAL FORM ONLY — `jsonld_patch` must follow these rules:\n" +
        "  - predicate keys MUST be absolute IRIs (e.g. \"https://schema.org/text\"). Compact terms " +
        "like \"schema:text\" are REJECTED with HTTP 400.\n" +
        "  - \"@context\" at the top level is REJECTED. Resolve all prefixes client-side.\n" +
        "  - The only allowed JSON-LD keywords are \"@id\" (optional; if present it must equal the " +
        "request's resource_iri) and \"@type\" (string, array of strings, or null to remove every " +
        "rdf:type triple).\n" +
        "  - Property values follow JSON-LD value-object form: literals as " +
        "{\"@value\": \"…\", \"@language\": \"…\"|\"@type\": \"<datatype-iri>\"} or bare strings; IRI " +
        "values as {\"@id\": \"…\"}.\n\n" +
        "RFC 7396 array-replace applies: setting a multivalued property in the patch replaces every " +
        "existing value. For additive multivalued operations (e.g. add ONE more schema:children " +
        "without losing the others), use the property-value tools (`add_property_value` / " +
        "`remove_property_value`).\n\n" +
        "WORKING EXAMPLE:\n\n" +
        "  {" + exampleTarget + "\n" +
        "    \"context_iri\": \"https://sempods.org/alice/contacts\",\n" +
        "    \"resource_iri\": \"https://sempods.org/alice/contacts/bob\",\n" +
        "    \"jsonld_patch\": {\n" +
        "      \"https://schema.org/text\": [{\"@value\": \"updated\"}],\n" +
        "      \"https://schema.org/oldField\": null\n" +
        "    }\n" +
        "  }\n\n" +
        "Requires the `<context_iri>#write` (or `#manage`) scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the context (graph) the resource lives in."),
        "resource_iri" to prop("string", "Absolute IRI of the resource to patch — local or external (did:, urn:, foreign https://…)."),
        "jsonld_patch" to prop("object", "Strict canonical JSON-LD merge-patch body. Absolute-IRI predicate keys only. No \"@context\". Only \"@id\" (optional, must match resource_iri) and \"@type\" keywords allowed; a null value removes that property. Example: {\"https://schema.org/text\":[{\"@value\":\"updated\"}]}."),
        "if_match" to prop("string", "Optional ETag for lost-update protection: pass the `etag` returned by `get_resource` (or by a prior write). Returns a precondition error if the resource changed since then. Omit for an unconditional patch."),
      ),
      required = listOf("context_iri", "resource_iri", "jsonld_patch"),
    ),
    writeTool(
      "delete_resource",
      "Delete a resource from ONE context$oneTarget. ALWAYS confirm the target resource_iri and " +
        "context_iri with the user before calling. Optional `if_match` makes the delete conditional " +
        "(no-op-safe lost-update protection). Requires the `<context_iri>#write` (or `#manage`) " +
        "scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the context (graph) the resource lives in."),
        "resource_iri" to prop("string", "Absolute IRI of the resource to delete — local or external (did:, urn:, foreign https://…)."),
        "if_match" to prop("string", "Optional ETag for lost-update protection: pass the `etag` from `get_resource` (or a prior write). Returns a precondition error if the resource changed since then. Omit for an unconditional delete."),
      ),
      required = listOf("context_iri", "resource_iri"),
    ),
    // TODO: batch sibling `add_property_values` (plural) taking a `values: array` argument to
    //  amortise MCP round-trip overhead when a caller needs to append many values to the same slot.
    //  v1 punts and lets clients call `add_property_value` in a loop. Revisit only when an actual
    //  workload shows the loop dominating end-to-end latency.
    writeTool(
      "add_property_value",
      "Add ONE value to the slot (subject_iri, predicate_iri) inside ONE context$oneTarget, without " +
        "disturbing existing values. The canonical way to extend a multivalued property (e.g. add one " +
        "more `schema:children` to a person) — `update_resource` cannot do this because RFC 7396 " +
        "replaces arrays wholesale.\n\n" +
        "RDF set semantics: adding a triple that already exists is a no-op. Calling this tool twice " +
        "with the same value is safe — the first call returns `outcome=created`, the second returns " +
        "`outcome=already_present`. Both are success outcomes; no `isError`.\n\n" +
        "`subject_iri` may be ANY absolute IRI — a local pod resource or an external one " +
        "(`did:web:…`, `urn:…`). This is the granular (per-value) path for writing triples about " +
        "external URIs; `create_resource` / `update_resource` are the whole-resource counterparts.\n\n" +
        "`value` is a JSON-LD value object: `{\"@id\": \"<iri>\"}` for an IRI value, or " +
        "`{\"@value\": \"…\", \"@language\": \"…\"}` / `{\"@value\": \"…\", \"@type\": \"…\"}` for a " +
        "literal.\n\n" +
        "Requires the `<context_iri>#write` (or `#manage`) scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the writable context. Use one of the writable_contexts from `list_contexts`."),
        "subject_iri" to prop("string", "Absolute IRI of the subject. May be a local pod resource or an external URI (did:, urn:, …)."),
        "predicate_iri" to prop("string", "Absolute IRI of the predicate (e.g. https://schema.org/children)."),
        "value" to prop("object", "JSON-LD value object: {\"@id\":\"<iri>\"} for an IRI value, or {\"@value\":\"…\",\"@language\":\"…\"|\"@type\":\"<iri>\"} for a literal."),
        "if_match" to prop("string", "Optional slot ETag: pass the `etag` from `get_property_values` (single context) or from a prior property-value call. Returns a precondition error if the slot changed. Omit for an unconditional add (the default; safe under RDF set semantics)."),
      ),
      required = listOf("context_iri", "subject_iri", "predicate_iri", "value"),
    ),
    writeTool(
      "set_property_values",
      "Replace ALL values of the slot (subject_iri, predicate_iri) inside ONE context$oneTarget with " +
        "the provided array. The right tool for: editing a single literal (read, then call this with " +
        "the new value), shrinking a multivalued slot, or wholesale replacing the values of one " +
        "property without touching other properties of the same resource.\n\n" +
        "`values` is a JSON array of JSON-LD value objects; an empty array clears the slot.\n\n" +
        "Requires the `<context_iri>#write` (or `#manage`) scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the writable context."),
        "subject_iri" to prop("string", "Absolute IRI of the subject (local or external)."),
        "predicate_iri" to prop("string", "Absolute IRI of the predicate."),
        "values" to objectArray("Array of JSON-LD value objects. Empty array clears the slot."),
        "if_match" to prop("string", "Optional slot ETag: pass the `etag` from `get_property_values` (single context) or a prior property-value call. Returns a precondition error if the slot changed. Omit for an unconditional replace."),
      ),
      required = listOf("context_iri", "subject_iri", "predicate_iri", "values"),
    ),
    writeTool(
      "remove_property_value",
      "Remove exactly ONE IRI-valued edge `(subject_iri, predicate_iri, target_iri)` from the slot " +
        "inside ONE context$oneTarget. Other values of the same slot are untouched.\n\n" +
        "Idempotent: removing an edge that is already absent succeeds with " +
        "`outcome=already_absent` instead of failing. Use this for \"ensure this triple does not " +
        "exist\" patterns without a prior read.\n\n" +
        "Literal values cannot be removed with this tool — use `set_property_values` after reading " +
        "the slot client-side.\n\n" +
        "Requires the `<context_iri>#write` (or `#manage`) scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the writable context."),
        "subject_iri" to prop("string", "Absolute IRI of the subject."),
        "predicate_iri" to prop("string", "Absolute IRI of the predicate."),
        "target_iri" to prop("string", "Absolute IRI of the value to remove (IRI targets only)."),
      ),
      required = listOf("context_iri", "subject_iri", "predicate_iri", "target_iri"),
    ),
    writeTool(
      "clear_property_values",
      "Empty the slot — remove every value of `predicate_iri` on `subject_iri` inside ONE " +
        "context$oneTarget. Other predicates of the same subject, and other contexts, remain " +
        "intact.\n\n" +
        "Idempotent: an already-empty slot succeeds with `outcome=already_empty` instead of " +
        "failing.\n\n" +
        "Requires the `<context_iri>#write` (or `#manage`) scope.$authorizeHint",
      properties = mapOf(
        "context_iri" to prop("string", "Absolute IRI of the writable context."),
        "subject_iri" to prop("string", "Absolute IRI of the subject."),
        "predicate_iri" to prop("string", "Absolute IRI of the predicate."),
        "if_match" to prop("string", "Optional slot ETag: pass the `etag` from `get_property_values` (single context) or a prior property-value call. Returns a precondition error if the slot changed. Omit for an unconditional clear."),
      ),
      required = listOf("context_iri", "subject_iri", "predicate_iri"),
    ),
  )

  companion object {

    private val instances = ToolVariant.entries.associateWith { ToolCatalog(it) }

    fun of(variant: ToolVariant): ToolCatalog = instances.getValue(variant)
  }
}

private fun prop(type: String, description: String) = PropertySchema(type = type, description = description)

/** A `string[]` argument — the element type is declared so [ToolCatalog.validate] can enforce it. */
private fun stringArray(description: String) =
  PropertySchema(type = "array", description = description, items = ItemsSchema("string"))

/** An `object[]` argument. Same reason as [stringArray]: an array without `items` says nothing. */
private fun objectArray(description: String) =
  PropertySchema(type = "array", description = description, items = ItemsSchema("object"))
