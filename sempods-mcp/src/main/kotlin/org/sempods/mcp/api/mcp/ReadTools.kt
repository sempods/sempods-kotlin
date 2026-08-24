package org.sempods.mcp.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.sempods.mcp.audit.AuditLog
import org.sempods.mcp.auth.ServiceBearerVerifier
import org.sempods.mcp.core.PodToolExecutor
import org.sempods.mcp.core.PodToolFailure
import org.sempods.mcp.core.PodToolPlan
import org.sempods.mcp.core.ToolArguments
import org.sempods.mcp.core.ToolCallResult
import org.sempods.mcp.core.toolError
import org.sempods.mcp.core.toolText
import org.sempods.mcp.persist.ConnectionRegistryDao
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfileKey
import org.sempods.client.SempodsClientException
import org.sempods.mcp.pods.PodTokenProvider
import org.sempods.mcp.pods.isRetryablePodFailure
import org.sempods.mcp.pods.podIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * The read tools, as this service means them: **many pods**.
 *
 * What one pod answers is [PodToolExecutor]'s, shared with the pod-immanent surface. What is left
 * here is everything that only exists because a call can address several pods — resolving which
 * ones (all in the active profile, or the `targets` subset), fetching a fresh pod token per pod via
 * [PodTokenProvider], and assembling the **per-pod envelope**, where pods are queried concurrently
 * and each pod's failure is captured into its own entry rather than failing the whole call.
 *
 * Result shape (the `result` of a `tools/call`): a single text content block carrying
 * `{ "pods": [ { "pod", "ok": true, "result": … } | { "pod", "ok": false, "error": { "kind", "message", "status"? } } ] }`.
 *
 * `list_pods` is the one read tool with no executor branch: it answers from this service's own
 * registry and makes no pod call at all.
 */
class ReadTools(
  private val connectionRegistryDao: ConnectionRegistryDao,
  private val podTokenProvider: PodTokenProvider,
  private val executor: PodToolExecutor,
  private val objectMapper: ObjectMapper,
  private val mcpBaseUrl: String,
  private val auditLog: AuditLog,
) {

  suspend fun dispatch(toolName: String, arguments: JsonNode?, session: ServiceBearerVerifier.Session): ToolCallResult {
    val profile = ProfileKey(session.user, session.profile.ifBlank { PodKey.DEFAULT_PROFILE })
    if (toolName == "list_pods") {
      // Not an executor tool — it names the pods rather than asking any of them, so it is validated
      // here and answered from `connections`.
      hostedToolCatalog.validate(toolName, arguments)?.let { return refused(profile, toolName, it) }
      return listPods(profile)
    }
    // Parse and validate ONCE, before any pod is contacted: a bad argument is one tool-level error,
    // not the same message repeated in every fan-out entry.
    return when (val plan = executor.plan(toolName, arguments)) {
      is PodToolPlan.UnknownTool -> refused(profile, toolName, plan.message, detail = "unknown_tool")
      is PodToolPlan.InvalidArguments -> refused(profile, toolName, plan.message)
      is PodToolPlan.Call -> fanOut(profile, toolName, arguments, plan)
    }
  }

  /**
   * A tool-level refusal (the call never reached a pod), audited with a FIXED detail label —
   * never the message, which can embed argument values (the audit trail's no-secrets rule).
   */
  private fun refused(
    profile: ProfileKey,
    toolName: String,
    message: String,
    detail: String = "invalid_arguments",
  ): ToolCallResult {
    auditLog.toolCall(profile.user, profile.profile, toolName, targets = emptyList(), outcome = "error", detail = detail)
    return toolError(message)
  }

  // --- list_pods: pure registry read, no pod call ---

  private fun listPods(profile: ProfileKey): ToolCallResult {
    val connections = connectionRegistryDao.listForProfile(profile).sortedBy { it.pod }
    val pods = connections.map {
      linkedMapOf<String, Any?>(
        "pod" to it.pod,
        "issuer" to it.issuer,
        "scopes" to it.scopes.sorted(),
        // The identity this connection acts as ON THE POD. When it differs from your service
        // identity (foreign_identity), every read/write on this pod happens as `pod_subject`, not
        // as your sempods WebID — attribute results/writes accordingly. `subject_verified` is false
        // when the pod exposes no JWKS (the sub is trusted via the direct TLS token, not a signature).
        // `similar_to` is your sempods WebID that `pod_subject` *likely* denotes the same person as —
        // a weak hint (like `rdfs:seeAlso`), not an asserted `owl:sameAs` (null when not foreign).
        "pod_subject" to it.podSubject,
        "foreign_identity" to it.foreignIdentity,
        "subject_verified" to it.subjectVerified,
        "similar_to" to if (it.foreignIdentity) it.user else null,
        // The pod declared this connection's grant finished, so every call to it will fail until
        // the person reconnects. The dashboard says so to them; this says it to the agent, which
        // would otherwise retry the pod on every turn.
        "reconnect_required" to (it.deadGrantSince != null),
      )
    }
    val body = linkedMapOf<String, Any?>("pods" to pods)
    if (pods.isEmpty()) {
      body["hint"] = noPodsHint()
    } else {
      // `scopes` is only the access token's additive feature scopes (e.g. `public-read`); the
      // per-context read/write access a user granted is NOT carried there — it lives as pod-side
      // grants that can change over time, so it is not cached here. Point the caller at the
      // authoritative live view instead of letting a bare `scopes: [public-read]` read as "no access".
      // When any pod runs its own identity provider, also warn that you act there as a foreign WebID.
      body["note"] = if (connections.any { it.foreignIdentity }) "$SCOPES_NOTE $FOREIGN_IDENTITY_NOTE" else SCOPES_NOTE
    }
    auditLog.toolCall(profile.user, profile.profile, "list_pods", targets = emptyList(), outcome = "ok")
    return textResult(body)
  }

  // --- the fan-out ---

  /**
   * Resolves the target pods, queries them concurrently with [plan], and builds the per-pod
   * envelope. A pod with no usable token, or one whose call throws, contributes an `ok:false`
   * entry rather than aborting the others.
   */
  private suspend fun fanOut(
    profile: ProfileKey,
    toolName: String,
    arguments: JsonNode?,
    plan: PodToolPlan.Call,
  ): ToolCallResult {
    val connected = connectionRegistryDao.listForProfile(profile).associateBy { it.pod }
    // Tri-state `targets`: absent → fan out to all connected pods; an explicit `[]` → select none;
    // a non-empty list → exactly that subset. (Validation already guarantees a string array here.)
    val targetsArg = arguments?.get("targets")?.takeIf { it.isArray }
    val targets = if (targetsArg != null) {
      ToolArguments.stringList(arguments, "targets").map { it.trimEnd('/') }
    } else {
      connected.keys.toList()
    }

    if (targets.isEmpty()) {
      // Either no pod is connected (default fan-out over an empty set) or the caller explicitly
      // selected none via `targets: []`.
      val hint = if (targetsArg != null) "no pods selected (targets is empty)" else noPodsHint()
      auditLog.toolCall(profile.user, profile.profile, toolName, targets = emptyList(), outcome = "ok")
      return textResult(linkedMapOf("pods" to emptyList<Any>(), "hint" to hint))
    }

    val sortedTargets = targets.sorted()
    val entries = coroutineScope {
      sortedTargets.map { pod ->
        async { queryOnePod(profile, toolName, pod, connected[pod], plan) }
      }.awaitAll()
    }
    // Partial-error surfacing (M4): if any pod failed, flag the whole result as incomplete and list
    // the failed pods, so a caller cannot mistake a multi-pod read missing one pod for a complete one.
    val body = linkedMapOf<String, Any?>("pods" to entries)
    val failed = entries.filter { it["ok"] == false }.map { it["pod"] }
    if (failed.isNotEmpty()) {
      body["partial"] = true
      body["failed_pods"] = failed
    }
    // One audit row per tools/call (M6.4) — the vault-access / usage trail at tool-call granularity.
    val outcome = when {
      failed.isEmpty() -> "ok"
      failed.size == entries.size -> "error"
      else -> "partial"
    }
    auditLog.toolCall(profile.user, profile.profile, toolName, sortedTargets, outcome)
    return textResult(body)
  }

  private suspend fun queryOnePod(
    profile: ProfileKey,
    toolName: String,
    pod: String,
    connection: PodConnection?,
    plan: PodToolPlan.Call,
  ): Map<String, Any?> {
    if (connection == null) return podError(pod, "not_connected", "pod not connected for this profile")
    val token = try {
      podTokenProvider.validAccessToken(PodKey(profile.user, profile.profile, pod))
    } catch (e: CancellationException) {
      throw e // never swallow cancellation — let structured concurrency tear the request down
    } catch (e: Exception) {
      // A retryable failure (throttle, SSRF-block, or a pod that refused with anything but
      // `invalid_grant`) is not a dead token — reporting no_token would suggest a reconnect that
      // needlessly rotates the refresh-token family, for a pod that may simply be mid-deploy.
      if (e.isRetryablePodFailure()) return podError(pod, "pod_error", e.message ?: "pod fetch failed")
      logger.warn(e) { "pod token lookup failed for pod '$pod'" }
      null
    } ?: return podError(pod, "no_token", "no valid pod token — reconnect this pod at $mcpBaseUrl/_system/ui")
    return try {
      // The blocking executor runs on a virtual thread; `podIo` carries the trace across the hop and
      // wires coroutine cancellation to the socket. The classification below stays OUT here, where a
      // cancelled call still arrives as `CancellationException` — inside `podIo` it would look like
      // an ordinary socket failure and become a well-formed "the pod failed".
      val entry = linkedMapOf<String, Any?>("pod" to pod, "ok" to true, "result" to podIo { plan.execute(URI(pod), token) })
      // When this pod runs its own identity provider, mark that the result was produced acting as a
      // foreign WebID — so a caller reading e.g. list_contexts knows whose access it is looking at.
      // validAccessToken() above may have refreshed and BACKFILLED a legacy null podSubject; re-read
      // the row for the annotation in that one case so the first post-backfill read is not stale
      // (steady-state rows already carry podSubject → no extra read).
      val fresh = if (connection.podSubject == null)
        connectionRegistryDao.find(PodKey(profile.user, profile.profile, pod)) ?: connection
      else connection
      fresh.annotateForeignIdentity(entry)
      entry
    } catch (e: CancellationException) {
      throw e
    } catch (e: SempodsClientException) {
      logger.warn(e) { "read tool failed for pod '$pod'" }
      // The pod answered, so its status travels structurally — a 403 scope refusal stays
      // distinguishable from a 502 without regex-ing the message, the same as on the write path.
      // The message is the pod's own body, phrased for a model by the shared describer; falling
      // back to `e.message` only where there was no body to read (a refused connection).
      podError(
        pod, "pod_error",
        PodToolFailure.detail(toolName, e.statusCode, e.responseBody ?: e.message ?: "pod read failed"),
        e.statusCode,
      )
    } catch (e: Exception) {
      logger.warn(e) { "read tool failed for pod '$pod'" }
      podError(pod, "pod_error", e.message ?: "pod read failed")
    }
  }

  // --- helpers (the per-pod failure shape lives once in ToolEnvelope; the rest is shared) ---

  private fun podError(pod: String, kind: String, message: String, status: Int? = null) =
    ToolEnvelope.podError(pod, kind, message, status)
  private fun textResult(body: Any) = toolText(objectMapper, body)

  private fun noPodsHint(): String = "No pods connected. Connect one at $mcpBaseUrl/_system/ui."

  companion object {
    private val logger = KotlinLogging.logger {}

    private const val SCOPES_NOTE =
      "`scopes` are the access token's additive feature scopes (e.g. `public-read`), not the " +
        "per-context grants. For the authoritative read/write access on each context, call " +
        "`list_contexts` — a pod can grant read/write on specific contexts without any of it " +
        "appearing in `scopes`."

    private const val FOREIGN_IDENTITY_NOTE =
      "At least one pod has `foreign_identity: true` — you act on it as its own `pod_subject` " +
        "WebID, not your sempods identity. Treat reads/writes there as that identity. `similar_to` " +
        "names your sempods WebID that `pod_subject` likely denotes the same person as — a weak " +
        "hint for correlating the two in a graph, not an asserted identity (not `owl:sameAs`)."
  }
}
