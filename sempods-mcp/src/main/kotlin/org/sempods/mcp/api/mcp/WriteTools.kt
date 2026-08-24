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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/**
 * The write / property-mutation tools, as this service means them: **one** pod, named explicitly.
 *
 * Unlike the reads these never fan out — each carries a required single `target` and a required
 * single `context_iri`, so a write lands in exactly one pod and one graph and can never be sprayed
 * across pods by accident. What one pod does with the call, and every argument check in front of it
 * (absolute IRIs, ETag normalization), is [PodToolExecutor]'s, shared with the pod-immanent surface.
 * What is left here is `target` — which pod, and is it one this user has connected — plus the token,
 * the single-pod envelope and the audit row.
 *
 * The pod stays the authority on the `<context_iri>#write` scope and on ETag preconditions: a
 * 403/412/400 comes back as the per-pod error carrying the pod's HTTP `status`. Result shape mirrors
 * the read tools but for a single pod: `{ "pod", "ok": true, "result": { … echoed ids, status,
 * etag?, response? } }` or an `ok:false` entry carrying `{ kind, message, status? }`. A success
 * envelope also gains `foreign_identity` + `pod_subject` when the write landed on the pod as a
 * foreign WebID (mirroring the read fan-out).
 */
class WriteTools(
  private val connectionRegistryDao: ConnectionRegistryDao,
  private val podTokenProvider: PodTokenProvider,
  private val executor: PodToolExecutor,
  private val objectMapper: ObjectMapper,
  private val mcpBaseUrl: String,
  private val auditLog: AuditLog,
) {

  suspend fun dispatch(toolName: String, arguments: JsonNode?, session: ServiceBearerVerifier.Session): ToolCallResult {
    val profile = ProfileKey(session.user, session.profile.ifBlank { PodKey.DEFAULT_PROFILE })

    // A tool-level refusal (the call never reached a pod), audited with a FIXED detail label —
    // never the message, which can embed argument values (the audit trail's no-secrets rule).
    fun refused(message: String, target: String? = null, detail: String = "invalid_arguments"): ToolCallResult {
      auditLog.toolCall(profile.user, profile.profile, toolName, targets = listOfNotNull(target), outcome = "error", detail = detail)
      return toolError(message)
    }

    // Schema, absolute IRIs and preconditions are all decided here, before anything is contacted —
    // deliberately only *argument* validation, never authorization: a malformed argument is this
    // service's business, who may write where is the pod's, and this layer transforms rather than
    // decides. `target` is the exception, and for the opposite reason: whether it is a pod this user
    // connected is this service's own state, not the pod's.
    val plan = when (val planned = executor.plan(toolName, arguments)) {
      is PodToolPlan.UnknownTool -> return refused(planned.message, detail = "unknown_tool")
      is PodToolPlan.InvalidArguments -> return refused(planned.message, ToolArguments.string(arguments, "target"))
      is PodToolPlan.Call -> planned
    }

    val target = ToolArguments.string(arguments, "target")?.trimEnd('/')
      ?: return refused("missing required argument: target")

    val key = PodKey(profile.user, profile.profile, target)
    // The registry lookup (mcp.connections) blocks a never-connected target as a tool-level error;
    // a connected pod whose token is gone is handled in writeEnvelope() as a per-pod error
    // (kind=no_token). The connection also carries the pod-local identity, so a write done as a
    // foreign WebID can be annotated on its envelope (mirroring the read fan-out).
    val connection = connectionRegistryDao.find(key)
      ?: return refused("pod not connected for this profile: $target (connect it at $mcpBaseUrl/_system/ui)", target, detail = "not_connected")

    val envelope = writeEnvelope(key, toolName, target, connection, plan)
    // One audit row per tools/call (M6.4) — a write targets exactly one pod; on failure the detail
    // carries the stable per-pod error kind (no_token | pod_error), never the message.
    auditLog.toolCall(
      key.user, key.profile, toolName, targets = listOf(target),
      outcome = if (envelope["ok"] == true) "ok" else "error",
      detail = ((envelope["error"] as? Map<*, *>)?.get("kind") as? String),
    )
    return toolText(objectMapper, envelope)
  }

  /** The single-pod write envelope; [dispatch] wraps it as the text result and audits the call. */
  private suspend fun writeEnvelope(
    key: PodKey,
    toolName: String,
    pod: String,
    connection: PodConnection,
    plan: PodToolPlan.Call,
  ): Map<String, Any?> {
    val token = try {
      podTokenProvider.validAccessToken(key)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A retryable failure (throttle, SSRF-block, or a pod that refused with anything but
      // `invalid_grant`) is not a dead token — reporting no_token would suggest a reconnect that
      // needlessly rotates the refresh-token family, for a pod that may simply be mid-deploy.
      if (e.isRetryablePodFailure()) return podError(pod, "pod_error", e.message ?: "pod fetch failed")
      logger.warn(e) { "pod token lookup failed for pod '$pod'" }
      null
    } ?: return podError(pod, "no_token", "no valid pod token — reconnect this pod at $mcpBaseUrl/_system/ui")
    return try {
      // `podIo` bridges to the blocking executor on a virtual thread; the classification below stays
      // outside it, where a cancelled call still arrives as `CancellationException`.
      val result = podIo { plan.execute(URI(pod), token) }
      val ok = linkedMapOf<String, Any?>("pod" to pod, "ok" to true, "result" to result)
      // Mirror the read fan-out: when the write happened as a foreign WebID, say so on the envelope
      // — the write landed on the pod as `pod_subject`, not the caller's sempods identity.
      // validAccessToken() may have refreshed and BACKFILLED a legacy null podSubject; re-read the row
      // for the annotation in that one case (steady-state rows already carry podSubject → no extra read).
      val fresh = if (connection.podSubject == null) connectionRegistryDao.find(key) ?: connection else connection
      fresh.annotateForeignIdentity(ok)
      ok
    } catch (e: CancellationException) {
      throw e
    } catch (e: SempodsClientException) {
      logger.warn(e) { "write tool failed for pod '$pod'" }
      // The pod's status travels structurally, so a 412 precondition failure stays distinguishable
      // from a 403 scope refusal on the envelope rather than only inside the message text. The
      // message is the pod's own body, phrased for a model by the shared describer; falling back to
      // `e.message` only where there was no body to read (a refused connection).
      podError(
        pod, "pod_error",
        PodToolFailure.detail(toolName, e.statusCode, e.responseBody ?: e.message ?: "pod write failed"),
        e.statusCode,
      )
    } catch (e: Exception) {
      logger.warn(e) { "write tool failed for pod '$pod'" }
      podError(pod, "pod_error", e.message ?: "pod write failed")
    }
  }

  private fun podError(pod: String, kind: String, message: String, status: Int? = null) =
    ToolEnvelope.podError(pod, kind, message, status)

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
