package org.sempods.mcp.audit

import org.sempods.mcp.persist.AuditEvent
import org.sempods.mcp.persist.AuditEventType
import org.sempods.mcp.persist.AuditLogDao
import org.sempods.mcp.persist.PodKey
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Date

/**
 * The audit emitter (M6.4): typed methods per event kind so call sites stay terse and the
 * field mapping cannot drift. **Synchronous but swallowing** — every emit runs the Mongo
 * insert inline (the module already does sync Mongo reads on the tool-call hot path, so one
 * small insert is comparable cost; a queue would add a drop policy, a shutdown flush, and a
 * thread), and an audit failure is logged and dropped, never propagated: auditing must not
 * fail the request path.
 *
 * `detail` values are fixed short labels, never free-form messages — see [AuditEvent].
 */
class AuditLog(
  private val dao: AuditLogDao,
  private val retentionDays: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val logger = KotlinLogging.logger {}

  // Flood guard for RATE_LIMITED rows only: a hammering client would otherwise turn its own
  // rejections into unbounded audit writes. One row per (user, profile) per minute.
  private val rateLimitedSampler = TokenBucketRateLimiter(1, clock)

  fun podConnected(user: String, profile: String, pod: String, ok: Boolean, detail: String? = null) =
    emit(AuditEventType.POD_CONNECT, user, profile, outcome = if (ok) "ok" else "error", pod = pod, detail = detail)

  fun podDisconnected(user: String, profile: String, pod: String) =
    emit(AuditEventType.POD_DISCONNECT, user, profile, outcome = "ok", pod = pod)

  fun podTokenRefreshed(key: PodKey, ok: Boolean, detail: String? = null) =
    emit(
      AuditEventType.POD_TOKEN_REFRESH, key.user, key.profile,
      outcome = if (ok) "ok" else "error", pod = key.pod, detail = detail,
    )

  fun serviceTokenRotated(user: String, profile: String, clientId: String) =
    emit(AuditEventType.SERVICE_TOKEN_ROTATE, user, profile, outcome = "ok", clientId = clientId)

  fun serviceTokenFamilyRevoked(user: String, profile: String, clientId: String, detail: String) =
    emit(AuditEventType.SERVICE_TOKEN_FAMILY_REVOKED, user, profile, outcome = "error", clientId = clientId, detail = detail)

  /** One row per `tools/call` — the vault-access / usage trail. [outcome]: `ok`|`partial`|`error`. */
  fun toolCall(user: String, profile: String, tool: String, targets: List<String>, outcome: String, detail: String? = null) =
    emit(AuditEventType.TOOL_CALL, user, profile, outcome = outcome, tool = tool, targets = targets, detail = detail)

  /** Sampled: at most one row per (user, profile) per minute, however hard the client hammers. */
  fun rateLimited(user: String, profile: String) {
    if (!rateLimitedSampler.tryAcquire("$profile|$user")) return
    emit(AuditEventType.RATE_LIMITED, user, profile, outcome = "error")
  }

  private fun emit(
    type: AuditEventType,
    user: String,
    profile: String,
    outcome: String,
    pod: String? = null,
    tool: String? = null,
    targets: List<String>? = null,
    clientId: String? = null,
    detail: String? = null,
  ) {
    runCatching {
      val now = Date(clock())
      dao.append(
        AuditEvent(
          ts = now, user = user, profile = profile, type = type, outcome = outcome,
          pod = pod, tool = tool, targets = targets, clientId = clientId, detail = detail,
          expiresAt = Date(now.time + retentionDays * 24 * 60 * 60 * 1000),
        ),
      )
    }.onFailure { logger.warn(it) { "audit write failed: type=$type user='$user'" } }
  }
}
