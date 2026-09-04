package org.sempods.api.pod.system.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.sempods.mcp.core.Capabilities
import org.sempods.mcp.core.ContentItem
import org.sempods.mcp.core.Implementation
import org.sempods.mcp.core.InitializeParams
import org.sempods.mcp.core.InitializeResult
import org.sempods.mcp.core.JsonRpcError
import org.sempods.mcp.core.JsonRpcErrorCodes
import org.sempods.mcp.core.JsonRpcErrorResponse
import org.sempods.mcp.core.JsonRpcException
import org.sempods.mcp.core.JsonRpcRequest
import org.sempods.mcp.core.JsonRpcResponse
import org.sempods.mcp.core.McpProtocol
import org.sempods.mcp.core.PodToolExecutor
import org.sempods.mcp.core.PodToolFailure
import org.sempods.mcp.core.PodToolPlan
import org.sempods.mcp.core.PromptsListResult
import org.sempods.mcp.core.PropertySchema
import org.sempods.mcp.core.ReauthorizeChallengeStore
import org.sempods.mcp.core.ResourcesListResult
import org.sempods.mcp.core.Tool
import org.sempods.mcp.core.ToolCallResult
import org.sempods.mcp.core.ToolCatalog
import org.sempods.mcp.core.ToolVariant
import org.sempods.mcp.core.ToolInputSchema
import org.sempods.mcp.core.ToolsCapability
import org.sempods.mcp.core.ToolsListResult
import org.sempods.mcp.core.isNotification
import com.google.inject.Inject
import org.sempods.client.SempodsClientException
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.logging.LogSafeText
import org.sempods.api.InvalidBearerException
import org.sempods.api.OAuthUpgradeRequiredException
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.api.pod.system.auth.buildProtectedResourceMetadata
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import jakarta.ws.rs.*
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * MCP (Model Context Protocol) endpoint for sempods.
 * Provides a JSON-RPC 2.0 interface for AI assistants to interact with pod data.
 * Each pod has its own MCP endpoint for decentralized access — one surface per pod, at a
 * fixed path.
 *
 * Auth model (Linked Open Data): anonymous callers may use read tools, restricted to the
 * pod's public contexts. Write tools (`create_resource` / `update_resource` /
 * `delete_resource`) require a valid pod-scoped bearer and return 401 with
 * `WWW-Authenticate` when none is present — that triggers the MCP client's OAuth flow
 * advertised at `.well-known/oauth-protected-resource`. An *invalid* bearer is rejected
 * for every call (we never silently downgrade a failed auth attempt to anonymous).
 *
 * ### What is here and what is not
 *
 * Route, authentication, OAuth discovery, the `authorize` tool and delegation. **The thirteen
 * data tools are not**: [PodToolExecutor] in `:sempods-mcp-core` runs them, over HTTP against this
 * server's own `apiBaseUrl` — the path an external client takes, with no special case. This class
 * therefore injects no pod service at all; what is left ([reauthorizeChallengeStore],
 * [refreshTokenStore], [webIdUriDeriver]) belongs to `authorize`, which is the one tool that reads
 * the incoming token's claims and means something different on every surface.
 *
 * The point is not brevity. The pod and the hosted `sempods-mcp` service used to be two
 * implementations of one tool contract, and only one of them was exercised by production traffic
 * from outside. Now there is one, and the sandbox this surface applies is literally the sandbox
 * `_system/…` applies, because it is the same request. The costs that buys — a tool call is two
 * HTTP requests, a reverse-proxy outage takes MCP down while the pod is up, and the deployment must
 * reach itself under its public URL — are named in `docs/mcp/endpoint.md`.
 */
@Path("{pod}/_system/mcp")
class McpEndpoint @Inject constructor(
  private val podToolExecutor: PodToolExecutor,
  private val reauthorizeChallengeStore: ReauthorizeChallengeStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val webIdUriDeriver: WebIdUriDeriver,
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  private val objectMapper = JsonMappers.default()

  /**
   * This pod's tool surface: the shared catalog in its single-pod variant. The hosted service in
   * `sempods-mcp` advertises the same fourteen tools from the same specs, plus the fan-out
   * vocabulary its [ToolVariant.MULTI_POD] variant adds.
   */
  private val toolCatalog = ToolCatalog.of(ToolVariant.SINGLE_POD)

  companion object {
    private val logger = KotlinLogging.logger {}
  }

  /**
   * Instructions are (re)built on every `initialize` call (per MCP session, typically once
   * per client connect). They include the pod name and — if a valid bearer is present — the
   * concrete list of granted contexts. That keeps the text short and lets the LLM know which
   * graphs it may actually read/write against, without us pinning any particular vocabulary.
   *
   * The context list comes from [contextsForInstructions], i.e. from the pod over HTTP, for the
   * same reason the tools do: what the session may reach is the pod's answer, and asking it twice
   * in two different ways is how the two answers start to differ.
   */
  private fun buildInstructions(pod: String, credentials: SempodsCredentials): String {
    val podBaseUrl = "${config.apiBaseUrl}${pod}"
    val contexts = contextsForInstructions(pod)

    val grantedBlock = when {
      contexts == null ->
        "- Contexts: (could not be listed right now — call `list_contexts`, which is authoritative)"

      contexts.entries.isEmpty() -> "- Contexts: (none — only the pod metadata is reachable)"

      else -> {
        val lines = contexts.entries.map { entry ->
          // `source` is the pod's own word for why a context is visible; `public` means the caller
          // sees it without a grant, and that is worth saying rather than showing a bare "read".
          val perm = if (entry.source == "public") "read (public)" else entry.permissions.joinToString(" + ")
          "    - ${entry.contextIri}  →  $perm"
        }
        "- Contexts:\n" + lines.joinToString("\n")
      }
    }

    val writableContexts = contexts?.writableContexts.orEmpty()
    val writableHint = when {
      // Not "you have no write access" — that would be a claim this text cannot currently make, and
      // a model told it has none will stop trying instead of calling `list_contexts` and finding out.
      contexts == null -> "- Your write access could not be determined right now. Call `list_contexts` " +
        "before writing; its `writable_contexts` is authoritative."

      writableContexts.isNotEmpty() -> "- You have write access to:\n" +
        writableContexts.joinToString("\n") { "    - $it" }

      else -> "- You have no write access on this session. To write, ask the user to reconnect " +
        "and grant write permission on the target context."
    }

    return """
      This MCP server exposes a single semantic pod (sempod) at $podBaseUrl. Every call is
      sandboxed to the contexts (named graphs) the user consented to — queries cannot see
      data in other contexts, writes cannot land there. Start with tools/list, then dispatch
      via tools/call.

      AUTH:
      - Anonymous callers (no bearer) may use the read tools, restricted to the pod's
        public contexts. Writes require a bearer.
      - Authenticated callers see their explicitly granted contexts ∪ (the pod's public
        contexts only if `public-read` is in the token's scope set). `public-read` is an
        additive scope that the consent UI pre-checks by default but the user may
        deselect; without it the bearer sees only its explicit grants.
      $grantedBlock
      - If the user needs to sign in, grant write access, or adjust existing grants,
        ask them to reconnect the MCP server (the client's standard "reconnect" / "/mcp"
        gesture). The server answers with HTTP 401 + `WWW-Authenticate` (RFC 9728
        metadata at $podBaseUrl/.well-known/oauth-protected-resource); the MCP client
        opens the OAuth consent dialog. Dynamic clients always see the dialog on
        reconnect with existing grants pre-checked — one click to confirm or tweak.
        After the flow completes, retry the user's original intent.

      ENTRY POINT — call `list_contexts` FIRST:
      - Returns the exact `context_iri` values this session can see and the permission
        level on each (`read`, `read+write`, `read+write+manage`). This is the
        authoritative source — some MCP clients do not surface this `instructions`
        text to the model, so `list_contexts` is the only reliable way to know which
        contexts are writable. `writable_contexts` is authoritative for BOTH the
        resource tools (`create_resource` / `update_resource` / `delete_resource`)
        and the property-value tools (`add_property_value` / `set_property_values` /
        `remove_property_value` / `clear_property_values`).

      READ TOOLS:
      - `sparql_select` returns SPARQL-Results-JSON (rows, variable bindings). Use it to
        DISCOVER what is in the pod (types, predicates, counts).
      - `sparql_graph` runs CONSTRUCT or DESCRIBE and returns JSON-LD.
      - `get_resource` fetches one KNOWN resource as canonical JSON-LD plus an `etag`. Prefer
        it over SPARQL when you already have the IRI and intend to edit — the `etag` feeds
        `update_resource`/`delete_resource`'s `if_match`. Optional `include_contexts=true`
        returns the per-context (provenance) form.
      - `get_property_values` reads one slot `(subject, predicate)` and, for a single
        context, returns a slot `etag` for the property-value tools' `if_match`.
      - For provenance-sensitive reads ("which context did this triple come from?") use
        `get_resource` with `include_contexts=true` or `sparql_graph` with a `GRAPH` clause;
        otherwise default to the merged form.

      DISCOVERY — do this before assuming any vocabulary:
      1. List types present in the pod:
           SELECT DISTINCT ?type WHERE { ?s a ?type } LIMIT 100
      2. For a chosen type, list predicates actually used on its instances:
           SELECT DISTINCT ?p WHERE { ?s a <TYPE> ; ?p ?o } LIMIT 100
      3. For a single known resource, fetch ALL its properties in one call (avoids guessing
         `name` vs `label` vs `firstName`):
           DESCRIBE <RESOURCE_IRI>
         or equivalently
           CONSTRUCT { <RESOURCE_IRI> ?p ?o } WHERE { <RESOURCE_IRI> ?p ?o }

      Do NOT assume a specific vocabulary (schema.org, FOAF, Dublin Core, …) without
      verifying via discovery first. Different pods use different vocabularies.

      WRITE TOOLS — `create_resource` / `update_resource` / `delete_resource`:
      - Require a `<context_iri>#write` (or `#manage`) OAuth scope on the target context.
      $writableHint
      - The `context_iri` you use when calling a write tool MUST be one of the writable
        contexts listed above. Do not invent or derive IRIs — the server rejects writes
        to any other context with 403.
      - BEFORE calling a write tool, confirm the target `context_iri` with the user unless
        (a) the user's message named it explicitly, or (b) exactly one writable context
        is available and the user's intent clearly matches what that context is for.
      - `resource_iri` may be ANY absolute IRI — a resource in this pod, an external URI
        (`did:`, `urn:`, foreign `https://...`), or even one of this pod's own control-plane
        IRIs. What a statement is *about* is independent of where it is stored: the
        `context_iri` decides that, and its write scope is what governs the call.
      - `create_resource` upserts (replaces the resource's statements in the context). The
        `jsonld` argument may still carry "@context" + compact terms — `create_resource`
        runs full JSON-LD expansion on input.
      - `update_resource` applies a STRICT RFC 7396 merge-patch on the resource's CANONICAL
        JSON-LD shape (absolute IRI predicate keys, no top-level "@context", value objects
        as values). Compact terms like "schema:name" and "@context" are REJECTED with HTTP
        400 — clients must resolve prefixes before calling. RFC 7396 replaces multivalued
        properties WHOLESALE — for additive multivalued operations use the property-value
        tools instead.

      PROPERTY-VALUE TOOLS — `add_property_value` / `set_property_values` /
      `remove_property_value` / `clear_property_values`:
      - Operate at triple granularity on the slot `(subject_iri, predicate_iri)` within
        one `context_iri`.
      - `subject_iri` does NOT have to live in this pod's namespace — it may be any external
        URI (`did:web:bob.example`, `urn:isbn:...`, etc.). These are the GRANULAR (per-value)
        path for triples about external URIs; `create_resource` / `update_resource` /
        `delete_resource` are the WHOLE-RESOURCE path for the same external IRIs.
      - All arguments must be absolute IRIs. `predicate_iri` is the full property URI
        (e.g. `https://schema.org/children`, not `schema:children`).

      CHOOSING A WRITE TOOL:
      - Replace the whole resource / set several fields at once →
        `update_resource` (RFC 7396 merge-patch on the resource's canonical JSON-LD).
      - Add ONE more value to a multivalued property without losing the others →
        `add_property_value`. Idempotent under RDF set semantics: calling it twice with
        the same `value` is safe — the second call returns `outcome=already_present`
        rather than failing.
      - Replace ALL values of a single property → `set_property_values`. Also the path
        for editing a single literal (see "Literal read-modify-write" below).
      - Remove ONE IRI-valued edge → `remove_property_value`.
      - Empty a property entirely → `clear_property_values`.

      LITERAL READ-MODIFY-WRITE — editing a single literal:
      Literals have no addressable identity (you cannot URL-encode "Bob"), so there
      is no `remove_literal_value` tool. Use this 3-step pattern instead:
        1. Read the current slot — easiest is `get_property_values` (it also returns the
           slot `etag` for an optional `if_match` on step 3).
        2. Replace the target literal client-side (e.g. "Bob" → "Bob Smith").
        3. Call `set_property_values` with the full updated array of value objects.
      The same pattern works to edit one literal among many while preserving the others.

      DUPLICATE `add_property_value` IS A NO-OP:
      Calling `add_property_value` twice with the same `value` object is intentional and
      cheap: the first call returns `outcome=created`, the second returns
      `outcome=already_present`. Both are success outcomes — no isError. Lean on this for
      "ensure this triple exists" patterns instead of read-before-write.

      CONDITIONAL WRITES — `if_match`:
      - `update_resource`, `delete_resource`, `add_property_value`, `set_property_values`,
        and `clear_property_values` accept an optional `if_match` string. Get the ETag from a
        prior read — `get_resource` for whole resources, `get_property_values` (single
        context) for slots — or from the `etag` returned by the create/update and
        property-value write tools (`delete_resource` consumes an `if_match` but returns no
        `etag` — the resource is gone). Surrounding quotes / a `W/` prefix are tolerated.
      - On mismatch the tool surfaces an `isError: true` precondition result. Re-read the
        current state and decide.
      - Omitting `if_match` is the default — writes go through unconditionally.
      - `create_resource` accepts `if_none_match: "*"` for create-or-fail (fails if the
        resource already exists) instead of its default upsert.
      - `remove_property_value` does NOT take `if_match`: single-edge removal is idempotent
        (present or not, the outcome is the same), so optimistic-concurrency control adds
        nothing.

      REJECTED IN SPARQL: INSERT / DELETE / LOAD / CLEAR / CREATE / DROP / COPY / MOVE / ADD
      and SERVICE (SSRF protection). Use the write tools for mutations.
    """.trimIndent()
  }

  /** What [buildInstructions] needs out of `list_contexts`, and nothing else. */
  private data class InstructionContexts(
    val entries: List<InstructionContext>,
    val writableContexts: List<String>,
  )

  private data class InstructionContext(
    val contextIri: String,
    val permissions: List<String>,
    val source: String,
  )

  /**
   * The session's contexts, read through the same `list_contexts` tool the model can call.
   *
   * **Returns null instead of throwing when the pod cannot be reached.** `initialize` is the
   * handshake: if it fails, the MCP client has no session at all and the user sees a server that
   * will not connect. The instructions are advisory text — the block they carry is one a client may
   * not even show the model, which is why the text itself names `list_contexts` as authoritative —
   * so losing it must not cost the connection. The caller says so in the text rather than
   * pretending the answer was "none".
   */
  private fun contextsForInstructions(pod: String): InstructionContexts? {
    val plan = podToolExecutor.plan("list_contexts", objectMapper.createObjectNode())
    if (plan !is PodToolPlan.Call) return null
    return try {
      val body = objectMapper.valueToTree<JsonNode>(plan.execute(URI("${config.apiBaseUrl}$pod"), bearerToken()))
      InstructionContexts(
        entries = body.path("contexts").mapNotNull { node ->
          val iri = node.path("context_iri").takeIf { it.isTextual }?.asText() ?: return@mapNotNull null
          InstructionContext(
            contextIri = iri,
            permissions = node.path("permissions").mapNotNull { it.takeIf { p -> p.isTextual }?.asText() },
            source = node.path("source").takeIf { it.isTextual }?.asText().orEmpty(),
          )
        },
        writableContexts = body.path("writable_contexts").mapNotNull { it.takeIf { c -> c.isTextual }?.asText() },
      )
    } catch (e: Exception) {
      logger.warn(e) { "[mcp] could not list contexts for the initialize instructions on pod '$pod'" }
      null
    }
  }

  // ─── OAuth discovery probed at the MCP URL (MCP 2025-11-25) ──────────────
  // Clients (e.g. claude.ai web) proactively probe `.well-known/oauth-protected-resource`
  // under the MCP URL before they see a 401, so the WWW-Authenticate `resource_metadata`
  // hint alone isn't enough. The body is the pod-level one — `resource` is the pod URL,
  // which remains the unit of access control, and `authorization_servers` points at the
  // pod's single issuer. There is deliberately no AS-metadata route here: this URL is not
  // an issuer identifier, and serving one under it would contradict RFC 8414 §3.3.

  @GET
  @Path(".well-known/oauth-protected-resource")
  @Produces(MediaType.APPLICATION_JSON)
  fun mcpProtectedResourceMetadata(@PathParam("pod") pod: String): Response {
    val podDbo = fetchPodOrThrow(pod)
    return buildProtectedResourceMetadata(
      podDbo, config.apiBaseUrl,
      publicContextsCount = podFacade.getPublicContexts(podName = podDbo.name).size,
    )
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  fun handleMcpRequest(
    @PathParam("pod") pod: String,
    requestBody: String
  ): Response {
    // 2000 chars is enough to capture a full `tools/call` body with arguments; we keep
    // the cap because SPARQL-heavy bodies can run into many KB and would spam the log.
    // `params._meta` is redacted first — ChatGPT puts user geolocation, session IDs,
    // and organization IDs in there and we don't want that PII in the log stream.
    val podForLog = LogSafeText.of(pod)
    logger.info { "MCP request for pod '$podForLog': ${LogSafeText.of(redactMetaForLog(requestBody))}" }

    var requestId: Any? = null
    var auditMethod: String? = null
    var auditToolName: String? = null
    try {

      val request = objectMapper.readValue<JsonRpcRequest>(requestBody)
      requestId = request.id
      // Used for the audit lines alone, so they are escaped here rather than at each of the eight.
      auditMethod = LogSafeText.of(request.method)
      auditToolName = (request.params?.get("name") as? String)?.takeIf { it.isNotBlank() }?.let(LogSafeText::of)

      if (isNotification(request.id)) {
        // Notification (no response expected) — separate audit outcome so it does
        // not get conflated with synchronous calls when reading audit streams.
        // The pod still has to exist: 202 asserts the input was accepted, and a
        // notification addressed to a pod that does not exist can never be. This
        // is deliberately `fetchPodOrThrow` and not `authenticate` — a notification
        // carries no response a client could read a 401 from, and MCP clients send
        // `notifications/initialized` anonymously. The 404 leaves through the
        // WebApplicationException branch below, which answers with a JSON-RPC error
        // carrying no id — what MCP's Streamable HTTP transport prescribes for a
        // notification POST the server cannot accept.
        fetchPodOrThrow(pod)
        logger.info { "[mcp/audit] outcome=notification pod='$podForLog' method='$auditMethod'" }
        return Response.status(Response.Status.ACCEPTED).build()
      }

      // Anonymous callers get public-read only. Write tools throw InvalidBearerException
      // for anonymous callers (→ 401 with WWW-Authenticate), which kicks the MCP client
      // into the OAuth flow advertised at oauth-protected-resource.
      val credentials = authenticate(pod)
      val authenticated = credentials.oauthClientId != null
      // Audit reports JWT truth, not the manage-cascade-expanded view, so
      // the count stays stable as the pod owner registers new descendants.
      val scopesCount = credentials.oauthRawScopes.size

      val result = handleMethod(pod, credentials, request)
      if (result == null) {
        logger.info {
          "[mcp/audit] outcome=accepted pod='$podForLog' method='$auditMethod' " +
              "authenticated=$authenticated scopes=$scopesCount"
        }
        return Response.status(Response.Status.ACCEPTED).build()
      }

      // For tools/call we distinguish between a successful tool execution
      // (`outcome=tool_call`) and a tool that returned `isError=true`
      // (`outcome=tool_error`). Otherwise an authenticated write attempt
      // rejected by the per-context scope check would look identical to a
      // successful call in the audit stream.
      val toolErrored = (result as? ToolCallResult)?.isError == true
      val outcome = when (auditMethod) {
        "initialize" -> "initialize"
        "tools/list" -> "tools_list"
        "tools/call" -> if (toolErrored) "tool_error" else "tool_call"
        "resources/list" -> "resources_list"
        "prompts/list" -> "prompts_list"
        else -> "method_$auditMethod"
      }
      logger.info {
        "[mcp/audit] outcome=$outcome pod='$podForLog' method='$auditMethod' " +
            "tool='${auditToolName ?: "(n/a)"}' authenticated=$authenticated scopes=$scopesCount " +
            "client_id='${credentials.oauthClientId ?: "(anon)"}'"
      }

      val response = JsonRpcResponse(
        jsonrpc = "2.0",
        id = request.id,
        result = result
      )
      return Response.ok(objectMapper.writeValueAsString(response)).build()

    } catch (e: InvalidBearerException) {
      // Two 401 paths to distinguish in the audit stream:
      // - OAuthUpgradeRequiredException (subclass): caller invoked the synthetic
      //   `authorize` tool while anonymous or public-read-only, or with
      //   `reauthorize=true`. This is a PLANNED upgrade signal — log as
      //   `outcome=auth_trigger`.
      // - Plain InvalidBearerException: missing/manipulated/stale bearer. Log
      //   as `outcome=error error=invalid_bearer`. The fact that the call was
      //   `tools/call authorize` is incidental — the 401 was caused by the
      //   bad bearer at request entry, not by an upgrade request.
      val isUpgrade = e is OAuthUpgradeRequiredException
      val auditLine = if (isUpgrade) {
        logger.info { "[mcp] OAuth upgrade requested via `authorize` tool for pod '${LogSafeText.of(e.podName)}'" }
        "[mcp/audit] outcome=auth_trigger pod='$podForLog' method='${auditMethod ?: "(unparsed)"}' " +
            "tool='authorize' http_status=401"
      } else {
        logger.info { "[mcp] Invalid bearer for pod '${LogSafeText.of(e.podName)}' — returning 401 with WWW-Authenticate" }
        "[mcp/audit] outcome=error pod='$podForLog' method='${auditMethod ?: "(unparsed)"}' " +
            "tool='${auditToolName ?: "(n/a)"}' error=invalid_bearer http_status=401"
      }
      logger.info { auditLine }
      val errorMessage = if (isUpgrade) {
        "OAuth upgrade required: existing session cannot perform this operation"
      } else {
        "Unauthorized: invalid or expired bearer token"
      }
      val errorResponse = JsonRpcErrorResponse(
        jsonrpc = "2.0",
        id = requestId,
        error = JsonRpcError(
          code = JsonRpcErrorCodes.UNAUTHORIZED,
          message = errorMessage,
          data = mapOf(
            "hint" to "start (or restart) the OAuth flow advertised at /.well-known/oauth-protected-resource",
          ),
        )
      )
      return Response.status(Response.Status.UNAUTHORIZED)
        .header("WWW-Authenticate", buildBearerChallenge(podName = e.podName))
        .entity(objectMapper.writeValueAsString(errorResponse))
        .type(MediaType.APPLICATION_JSON)
        .build()
    } catch (e: JsonRpcException) {
      logger.info {
        "[mcp/audit] outcome=error pod='$podForLog' method='${auditMethod ?: "(unparsed)"}' " +
            "tool='${auditToolName ?: "(n/a)"}' error=jsonrpc_${e.code}"
      }
      val errorResponse = JsonRpcErrorResponse(
        jsonrpc = "2.0",
        id = requestId,
        error = JsonRpcError(
          code = e.code,
          message = e.message,
          data = e.data
        )
      )
      return Response.ok(objectMapper.writeValueAsString(errorResponse)).build()
    } catch (e: WebApplicationException) {
      // A JAX-RS status thrown out of the handler itself — in practice `authenticate(pod)` ->
      // `fetchPodOrThrow(pod)` raising 404 for a pod name that does not exist. Tool bodies never
      // reach here (they translate WebApplicationException into an `isError` tool result), so a
      // 404 at this level is about the pod, not a resource inside it. Without this branch the
      // generic handler below answered -32603/500 and a caller could not tell a typo'd pod name
      // from a broken server. `InvalidBearerException` is a subclass and is handled above.
      val status = e.response?.status ?: 500
      val podUnknown = status == 404
      val auditError = if (podUnknown) "pod_not_found" else "http_" + status
      logger.info {
        "[mcp/audit] outcome=error pod='$podForLog' method='${auditMethod ?: "(unparsed)"}' " +
            "tool='${auditToolName ?: "(n/a)"}' error=$auditError http_status=$status"
      }
      val errorResponse = JsonRpcErrorResponse(
        jsonrpc = "2.0",
        id = requestId,
        error = JsonRpcError(
          // -32002 is MCP's "resource not found"; everything else keeps the generic server error.
          code = if (podUnknown) JsonRpcErrorCodes.RESOURCE_NOT_FOUND else JsonRpcErrorCodes.INTERNAL_ERROR,
          message = if (podUnknown) "Unknown pod '$pod'" else "Request failed with HTTP $status",
          data = if (podUnknown) {
            mapOf("hint" to "check the pod name in the MCP server URL")
          } else {
            e.response?.entity?.toString() ?: e.message
          },
        )
      )
      return Response.status(status)
        .entity(objectMapper.writeValueAsString(errorResponse))
        .type(MediaType.APPLICATION_JSON)
        .build()
    } catch (e: Exception) {
      logger.error(e) { "Error handling MCP request for pod '$podForLog'" }
      logger.info {
        "[mcp/audit] outcome=error pod='$podForLog' method='${auditMethod ?: "(unparsed)"}' " +
            "tool='${auditToolName ?: "(n/a)"}' error=internal http_status=500"
      }
      val errorResponse = JsonRpcErrorResponse(
        jsonrpc = "2.0",
        id = requestId,
        error = JsonRpcError(
          code = JsonRpcErrorCodes.INTERNAL_ERROR,
          message = "Internal error",
          data = e.message,
        )
      )
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
        .entity(objectMapper.writeValueAsString(errorResponse))
        .build()
    }
  }

  /**
   * Returns [rawBody] with `params._meta` replaced by the literal string
   * `"<redacted>"`, then truncated to 2000 chars. Used for the
   * `MCP request for pod ...:` log line so client telemetry (notably ChatGPT's
   * `openai/userLocation` GPS coordinates and session IDs) does not leak into
   * server logs. On any parse failure, falls back to the raw body — the log
   * line is best-effort, not security-critical (the bearer is verified
   * independently). Keep this cheap; it runs once per MCP request.
   */
  @Suppress("UNCHECKED_CAST")
  private fun redactMetaForLog(rawBody: String): String {
    return try {
      val tree = objectMapper.readValue(rawBody, Map::class.java) as MutableMap<String, Any?>
      val params = tree["params"] as? MutableMap<String, Any?>
      if (params != null && params.containsKey("_meta")) {
        params["_meta"] = "<redacted>"
      }
      objectMapper.writeValueAsString(tree).take(2000)
    } catch (_: Exception) {
      rawBody.take(2000)
    }
  }

  private fun handleMethod(
    pod: String,
    credentials: SempodsCredentials,
    request: JsonRpcRequest
  ): Any? {
    return when (request.method) {
      "initialize" -> handleInitialize(pod, credentials, request.params)
      // TODO: a `notifications/initialized` that *does* carry an `id` is a request, and answering
      //  it with 202 and an empty body leaves the client waiting for a response that never comes.
      //  The `isNotification` check above already catches the well-formed case, so this branch only
      //  fires on the malformed one; fix it by answering an empty result instead of `null`.
      "notifications/initialized" -> {
        logger.info { "MCP notifications/initialized received for pod '$pod'" }
        null
      }

      "tools/list" -> handleToolsList(credentials)
      "tools/call" -> handleToolsCall(pod, credentials, request.params)
      // ChatGPT and Open-Code probe these standard MCP discovery methods
      // alongside `tools/list`. sempods does not expose any resources or
      // prompts (everything is a tool), but answering with an empty list keeps
      // the audit stream clean and the clients happy.
      "resources/list" -> ResourcesListResult()
      "prompts/list" -> PromptsListResult()
      else -> throw JsonRpcException(
        code = JsonRpcErrorCodes.METHOD_NOT_FOUND,
        message = "Method not found: ${request.method}"
      )
    }
  }

  private fun handleInitialize(pod: String, credentials: SempodsCredentials, params: Map<String, Any>?): InitializeResult {
    logger.info { "MCP initialize request for pod '$pod'" }

    val initializeParams = try {
      objectMapper.convertValue(params ?: emptyMap<String, Any>(), InitializeParams::class.java)
    } catch (e: Exception) {
      throw JsonRpcException(
        code = JsonRpcErrorCodes.INVALID_PARAMS,
        message = "Invalid params",
        data = mapOf("hint" to "Expected initialize params: protocolVersion, capabilities, clientInfo")
      )
    }

    val requested = initializeParams.protocolVersion
    if (requested.isBlank()) {
      throw JsonRpcException(
        JsonRpcErrorCodes.INVALID_PARAMS,
        "Unsupported protocol version",
        data = mapOf("supported" to McpProtocol.SUPPORTED),
      )
    }

    val negotiated = McpProtocol.negotiate(requested)

    return InitializeResult(
      protocolVersion = negotiated,
      capabilities = Capabilities(
        tools = ToolsCapability(listChanged = true),
      ),
      serverInfo = Implementation(name = "sempods-mcp-server", version = "1.0.0"),
      instructions = buildInstructions(pod = pod, credentials = credentials)
    )
  }

  private fun handleToolsList(credentials: SempodsCredentials): ToolsListResult =
    ToolsListResult(tools = buildTools(credentials))

  /**
   * The advertised tool catalog. Single source of truth for BOTH `tools/list` and the server-side
   * argument validation in [handleToolsCall]: a tool's `inputSchema.properties` keys (plus its
   * `additionalProperties` flag) are what `tools/list` advertises AND what we enforce, so the two
   * cannot drift. Only the synthetic `authorize` description varies with auth state; every schema is
   * static.
   */
  private fun buildTools(credentials: SempodsCredentials): List<Tool> =
    listOf(authorizeTool(credentials)) + toolCatalog.readTools() + toolCatalog.writeTools()

  /**
   * The synthetic `authorize` tool, which is this surface's own and not in the shared catalog.
   *
   * Visible to anonymous and authenticated callers alike: a caller without write capability gets
   * the MCP-standard 401 with `WWW-Authenticate` when calling it (which starts the client's OAuth
   * flow), an authenticated caller with context-scoped grants gets a no-op response confirming the
   * session. `reauthorize=true` forces a fresh flow even when already authenticated — that is how a
   * caller asks for contexts the current grant does not cover (incremental authorization).
   *
   * Always visible rather than hidden-when-authorized, so `tools/list` stays stable across auth
   * state and no `tools/list_changed` notification is owed. That also makes its description the one
   * piece of the tool surface that depends on the caller, which is why it cannot live in the
   * memoized catalog.
   */
  private fun authorizeTool(credentials: SempodsCredentials): Tool {
    val authorizedHint = if (needsOAuthUpgrade(credentials)) {
      "You are NOT authorized for write operations on this pod. " +
          "Call this tool when the user wants to write data, see private contexts, " +
          "or upgrade the current anonymous/public-read-only session — the server replies " +
          "with HTTP 401 + WWW-Authenticate, which the MCP client should follow to start " +
          "the OAuth flow."
    } else {
      "You currently hold an authorized session for this pod with context-scoped grants. " +
          "Calling this tool returns the active session info without side effects. " +
          "Pass `reauthorize: true` to force a fresh OAuth flow when the user wants to " +
          "grant access to additional contexts (the consent UI re-renders with existing " +
          "grants pre-checked)."
    }
    return Tool(
      name = "authorize",
      description = "Start (or restart) the OAuth authorization flow for this pod. " +
          "Concerned with scopes/grants (which contexts the session may read or write), " +
          "not with identity (that is handled by the underlying login). " +
          "Idempotent by default: when called without a valid bearer the server returns " +
          "HTTP 401 with a WWW-Authenticate Bearer challenge that points at the " +
          "pod's protected-resource metadata; the MCP client is expected to " +
          "interpret this as a signal to begin its OAuth flow. " +
          "When called with a valid bearer, returns a JSON description of the " +
          "active session (clientId, scopes count) — useful for verifying that " +
          "an interactive auth flow has actually completed. " +
          "Pass `reauthorize: true` to force the OAuth flow to start again even " +
          "when the session is already authorized; use this to request additional " +
          "contexts/scopes that the current grant does not cover. " +
          authorizedHint,
      inputSchema = AUTHORIZE_INPUT_SCHEMA,
    )
  }

  private fun handleToolsCall(
    pod: String,
    credentials: SempodsCredentials,
    params: Map<String, Any>?
  ): ToolCallResult {
    val name = params?.get("name") as? String
      ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing 'name' parameter")

    @Suppress("UNCHECKED_CAST")
    val rawArguments = (params["arguments"] as? Map<String, Any>) ?: emptyMap()
    // `JsonRpcRequest.params` arrives as a loose map, `PodToolExecutor.plan` reads a tree. Converted
    // once, here, rather than per tool: the executor's type checks are the schema's, and a
    // hand-rolled coercion in front of them would be the second opinion this milestone removes.
    val arguments: JsonNode = objectMapper.valueToTree(rawArguments)

    // Write tools require an authenticated caller; read tools are available anonymously
    // (public-read only). Throw before dispatch so the MCP 401 handler can emit the
    // WWW-Authenticate challenge.
    // `authorize` is the synthetic OAuth-trigger tool. Three upgrade paths take the
    // 401 route:
    // - anonymous (no bearer) → start a fresh OAuth flow
    // - public-read-only bearer → upgrade to a context-scoped token
    // - already-authorized caller passing `reauthorize=true` → restart the flow
    //   to request additional contexts/scopes (incremental authorization)
    // All three signal "user wants more than they have" and need the WWW-Authenticate
    // response. Distinct from a manipulated/stale bearer (which never reaches here
    // because authenticate(pod) up-stream already rejected it).
    when (name) {
      "create_resource", "update_resource", "delete_resource",
      "add_property_value", "set_property_values",
      "remove_property_value", "clear_property_values" -> {
        requireAuthenticatedOrThrow(credentials)
      }
      "authorize" -> {
        val reauthorize = arguments.path("reauthorize").asBoolean(false)
        val decision = decideAuthorizeToolCall(credentials, reauthorize)
        if (decision.startOAuthFlow) {
          if (decision.revokeRefreshTokens) revokeRefreshTokensForExplicitReauthorize(credentials)
          if (decision.recordReplayChallenge) recordReauthorizeChallenge(credentials)
          throw upgradeRequired(credentials.pod.name)
        }
      }
    }

    // `authorize` is this surface's own tool and deliberately not in the shared catalog, so its
    // schema check is here too — otherwise it would be the one tool whose advertised
    // `additionalProperties: false` is not enforced.
    if (name == "authorize") {
      unknownArgumentsRefusal(AUTHORIZE_INPUT_SCHEMA, arguments)?.let { return toolError(it) }
      return executeAuthorize(credentials)
    }

    // Everything else is the shared executor's: it validates against the same catalog this surface
    // advertised, applies the argument rules, and returns a call that has not touched a socket yet.
    return when (val plan = podToolExecutor.plan(name, arguments)) {
      // Kept as -32602 rather than the -32601 the method-level dispatch uses: an unknown *tool* is a
      // bad `params.name`, not an unknown JSON-RPC method, and clients have been reading this code
      // since before the catalog was shared. `plan.message` is the same text as before.
      is PodToolPlan.UnknownTool -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, plan.message)
      // A bad argument is a tool-level error, not a protocol error: the model can read it and fix
      // its own call. This is the one wire change the milestone makes on purpose — the coercion
      // helpers this replaces answered some tools with -32602 and others with `isError`.
      is PodToolPlan.InvalidArguments -> toolError("Error: ${plan.message}")
      is PodToolPlan.Call -> runTool(pod, name, plan)
    }
  }

  /**
   * Runs one planned tool call against this pod, over HTTP, and turns a refusal into a tool error.
   *
   * The address is the public one ([SempodsConfig.apiBaseUrl] plus the pod name) and the bearer is
   * the caller's, forwarded untouched — so authentication, the context sandbox and every scope check
   * happen exactly once, on the way back in, in the code REST callers already exercise. A missing
   * bearer stays missing: anonymous is a supported mode (the pod answers from its public contexts),
   * not a failure to paper over.
   *
   * [PodToolPlan.Call] lets failures propagate by design, so classifying them is this frame's job.
   * There is no `CancellationException` branch and none is missing: the hosted service needs one
   * because it bridges into a virtual thread through `podIo`, where a cancelled coroutine arrives as
   * an ordinary socket error. Here the whole request is blocking JAX-RS and there is no coroutine to
   * cancel.
   */
  private fun runTool(pod: String, toolName: String, plan: PodToolPlan.Call): ToolCallResult =
    try {
      val payload = plan.execute(URI("${config.apiBaseUrl}$pod"), bearerToken())
      ToolCallResult(content = listOf(ContentItem(type = "text", text = objectMapper.writeValueAsString(payload))))
    } catch (e: SempodsClientException) {
      logger.info {
        "[mcp] tool '${LogSafeText.of(toolName)}' on pod '$pod' refused " +
            "(${e.statusCode ?: "no status"}): ${LogSafeText.of(e.message.toString())}"
      }
      // The pod's own body, not the exception message: the message carries the URL that was dialled,
      // and this text goes to a model.
      toolError(PodToolFailure.describe(toolName, e.statusCode, e.responseBody ?: e.message.orEmpty()))
    } catch (e: Exception) {
      logger.error(e) { "[mcp] tool '${LogSafeText.of(toolName)}' on pod '$pod' failed" }
      toolError("Error: ${e.message ?: e.javaClass.simpleName}")
    }

  /**
   * The `additionalProperties: false` check for a schema the shared catalog does not carry.
   *
   * A conforming client already drops unknown top-level arguments, but relying on that is how a
   * half-honored call gets through: an unknown field (a hallucinated `filter`, a typo'd
   * `context_iri`) silently dropped would broaden a read or write the wrong data. Fail closed, the
   * way the strict body parsing on REST `POST /_system/find` does.
   */
  private fun unknownArgumentsRefusal(schema: ToolInputSchema, arguments: JsonNode): String? {
    if (schema.additionalProperties != false) return null
    val unknown = arguments.fieldNames().asSequence().toSet() - schema.properties.keys
    if (unknown.isEmpty()) return null
    return "Error: unknown argument(s): ${unknown.sorted().joinToString(", ")}. " +
      "Allowed: ${schema.properties.keys.sorted().joinToString(", ").ifEmpty { "(none)" }}."
  }

  /**
   * Returns `true` if the caller needs to start an OAuth flow to gain write
   * or private-read access. Cases:
   *
   * - Anonymous (no bearer at all): `oauthClientId == null`.
   * - Public-read-only bearer: `oauthRawScopes == { "public-read" }` — the caller
   *   carries a token but it confers no write or context-scoped read
   *   capability. They need to upgrade to a context-scoped grant. Use the raw
   *   JWT scope set so the answer reflects what the client was actually
   *   granted, not the manage-cascade-expanded view.
   * - Authenticated but no visible contexts: the bearer is syntactically valid, but the
   *   durable grants behind this slim token were revoked or cascaded away. Trigger OAuth again
   *   so the client can recover immediately instead of waiting for token expiry.
   *
   * Used by the `authorize` tool's tools/list description (to phrase the
   * hint correctly) and its tools/call branch (to decide whether to throw
   * `OAuthUpgradeRequiredException` or run the no-op acknowledgement).
   */
  private fun needsOAuthUpgrade(credentials: SempodsCredentials): Boolean =
    credentials.oauthClientId == null ||
        credentials.oauthRawScopes == setOf(PUBLIC_READ_SCOPE) ||
        credentials.restrictedContexts.orEmpty().isEmpty()

  /**
   * Pre-dispatch decision for the synthetic `authorize` tool.
   *
   * @property startOAuthFlow reply with 401 + `WWW-Authenticate` so the MCP client starts
   *   or restarts the OAuth flow.
   * @property recordReplayChallenge remember this 401 so the client's post-OAuth replay of
   *   the same tool call can return success instead of causing a second 401 loop.
   * @property revokeRefreshTokens invalidate existing refresh tokens for explicit
   *   re-authorization so clients cannot satisfy the 401 by silently rotating a token.
   */
  private data class AuthorizeToolDecision(
    val startOAuthFlow: Boolean,
    val recordReplayChallenge: Boolean = false,
    val revokeRefreshTokens: Boolean = false,
  )

  private fun decideAuthorizeToolCall(
    credentials: SempodsCredentials,
    reauthorize: Boolean,
  ): AuthorizeToolDecision {
    val needsUpgrade = needsOAuthUpgrade(credentials)

    if (!reauthorize) {
      return AuthorizeToolDecision(startOAuthFlow = needsUpgrade)
    }

    if (reauthorizeChallengeStore.consumeIfReplay(
        realm = credentials.pod.name,
        clientId = credentials.oauthClientId,
        sub = credentials.tokenSub,
        currentJti = credentials.tokenJti,
        currentIssuedAt = credentials.tokenIssuedAt,
      )) {
      return AuthorizeToolDecision(startOAuthFlow = false)
    }

    return AuthorizeToolDecision(
      startOAuthFlow = true,
      recordReplayChallenge = true,
      revokeRefreshTokens = credentials.oauthClientId != null,
    )
  }

  private fun upgradeRequired(podName: String): OAuthUpgradeRequiredException =
    OAuthUpgradeRequiredException(
      podName = podName,
      response = Response.status(401)
        .header("WWW-Authenticate", buildBearerChallenge(podName = podName))
        .entity("authorize tool: OAuth upgrade required")
        .type("text/plain")
        .build(),
    )

  private fun revokeRefreshTokensForExplicitReauthorize(credentials: SempodsCredentials) {
    val podId = podFacade.getPodId(credentials.pod.name) ?: return
    val clientId = credentials.oauthClientId ?: return
    val webId = credentials.tokenSub ?: return
    // Scope is intentionally broad for this pod/client/user: explicit reauthorization
    // means "review current consent", so parallel sessions with the same dynamic
    // client_id must not silently refresh around the consent UI. Broad over the person too,
    // not over the one URI this bearer happens to carry — a family minted under the twin would
    // otherwise keep refreshing around exactly that UI.
    val revoked = refreshTokenStore.revokeForUser(
      podId = podId,
      clientId = clientId,
      webIds = webIdUriDeriver.derivableAliases(webId),
    )
    if (revoked > 0) {
      logger.info {
        "[mcp] Revoked refresh tokens for explicit reauthorize: pod='${credentials.pod.name}', " +
            "client_id='$clientId', web_id='$webId', revoked=$revoked"
      }
    }
  }

  private fun recordReauthorizeChallenge(credentials: SempodsCredentials) {
    reauthorizeChallengeStore.record(
      realm = credentials.pod.name,
      clientId = credentials.oauthClientId,
      sub = credentials.tokenSub,
      jti = credentials.tokenJti,
    )
  }

  /**
   * Idempotent acknowledgement for an `authorize` tool-call by a caller that
   * already holds context-scoped grants and did NOT pass `reauthorize=true`.
   * Callers who need an upgrade (anonymous, public-read-only, or
   * `reauthorize=true`) never reach this path — they bail out with
   * `OAuthUpgradeRequiredException` before dispatch.
   */
  private fun executeAuthorize(credentials: SempodsCredentials): ToolCallResult {
    val body = linkedMapOf<String, Any>(
      "authorized" to true,
      "client_id" to (credentials.oauthClientId ?: ""),
      // Report the JWT-granted count, not the manage-cascade-expanded size.
      "scopes" to credentials.oauthRawScopes.size,
      "message" to "Session is already authorized. Pass reauthorize=true to request additional contexts.",
    )
    return ToolCallResult(
      content = listOf(
        ContentItem(
          type = "text",
          text = objectMapper.writeValueAsString(body),
        )
      )
    )
  }

  /** A tool-level failure: the model sees the text and can act on it, the JSON-RPC call succeeded. */
  private fun toolError(text: String): ToolCallResult =
    ToolCallResult(content = listOf(ContentItem(type = "text", text = text)), isError = true)

}

/**
 * The `authorize` tool's argument schema, shared with the hosted service's copy in shape but not in
 * text — see [McpEndpoint.authorizeTool] for why the tool itself stays out of the shared catalog.
 */
private val AUTHORIZE_INPUT_SCHEMA = ToolInputSchema(
  type = "object",
  properties = mapOf(
    "reauthorize" to PropertySchema(
      type = "boolean",
      description = "If true, force the OAuth flow to start again even if the session is already authorized. Use to request additional contexts/scopes that the current grant does not cover. Default: false.",
    ),
  ),
  required = emptyList(),
  additionalProperties = false,
)
