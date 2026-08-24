package org.sempods.mcp.persist

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import org.bson.Document
import java.util.Date
import java.util.concurrent.TimeUnit
import org.sempods.mcp.SempodsMcpCollections

/** The auditable event kinds (M6.4). Persisted by enum name — renaming is a breaking change. */
enum class AuditEventType {
  /** A pod was connected via the `/_system/ui` OAuth flow (or the attempt failed). */
  POD_CONNECT,
  /** A pod connection (vault row + registry entry) was removed. */
  POD_DISCONNECT,
  /** A pod token refresh/rotation was attempted — covers the background sweep and on-demand. */
  POD_TOKEN_REFRESH,
  /** The service's own refresh token was rotated (AI-client session). */
  SERVICE_TOKEN_ROTATE,
  /** A rotated service refresh token was replayed — the whole family was revoked. */
  SERVICE_TOKEN_FAMILY_REVOKED,
  /** One MCP `tools/call` — the vault-access / usage trail at tool-call granularity. */
  TOOL_CALL,
  /** A `tools/call` was rejected by the per-user quota (sampled, see `AuditLog.rateLimited`). */
  RATE_LIMITED,
}

/**
 * One audit row. Deliberately carries NO secret material: no tokens (plaintext, hash, or JTI),
 * no request bodies, no tool arguments, no SPARQL text, no IRIs beyond pod base URLs. [detail]
 * is restricted to short fixed labels (`issuer_mismatch`, `refresh_reuse`, `invalid_arguments`,
 * …) — never a free-form message, which could embed argument values.
 */
data class AuditEvent(
  val ts: Date,
  val user: String,
  val profile: String,
  val type: AuditEventType,
  /** `ok` | `partial` | `error` — `partial` only for fan-out tool calls. */
  val outcome: String,
  val pod: String? = null,
  val tool: String? = null,
  val targets: List<String>? = null,
  val clientId: String? = null,
  val detail: String? = null,
  /** TTL anchor, computed at write time (`ts` + retention) — see [AuditLogDao]. */
  val expiresAt: Date,
)

/**
 * The persistent audit trail (M6.4): one collection, append-only, retention-bounded by a Mongo
 * TTL index on the per-row [AuditEvent.expiresAt]. The anchor is computed at write time from
 * the configured retention, so the index never changes — a retention change affects only new
 * rows (consistent with the no-migrations stance).
 *
 * [append] may throw (Mongo down): swallowing is deliberately the emitter's job
 * (`audit/AuditLog`), so tests and the isolation review can still assert on a throwing DAO.
 */
/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `docs/persistence.md` §"Conventions" states.
 */
class AuditLogDao(db: MongoDatabase, collectionName: String = SempodsMcpCollections.AUDIT_LOG) {

  private val events = db.getCollection(collectionName)

  init {
    // The per-tenant trail; walked backwards for recent-first.
    events.createIndex(Indexes.ascending("user", "profile", "ts"))
    // Mongo TTL: prune rows once expiresAt passes.
    events.createIndex(Indexes.ascending("expiresAt"), IndexOptions().expireAfter(0, TimeUnit.SECONDS))
  }

  fun append(event: AuditEvent) {
    events.insertOne(event.toDocument())
  }

  /**
   * The tenant-keyed read: always filtered by [user] (and [profile] when given) — there is
   * deliberately no unscoped listing on this DAO.
   */
  fun listFor(user: String, profile: String? = null): List<AuditEvent> {
    val filter =
      if (profile == null) Filters.eq("user", user)
      else Filters.and(Filters.eq("user", user), Filters.eq("profile", profile))
    return events.find(filter).sort(Sorts.descending("ts")).map { it.toEvent() }.toList()
  }

  private fun AuditEvent.toDocument() = Document().apply {
    put("ts", ts)
    put("user", user)
    put("profile", profile)
    put("type", type.name)
    put("outcome", outcome)
    put("pod", pod)
    put("tool", tool)
    put("targets", targets)
    put("clientId", clientId)
    put("detail", detail)
    put("expiresAt", expiresAt)
  }

  private fun Document.toEvent() = AuditEvent(
    ts = getDate("ts") ?: Date(),
    user = getString("user"),
    profile = getString("profile"),
    type = AuditEventType.valueOf(getString("type")),
    outcome = getString("outcome"),
    pod = getString("pod"),
    tool = getString("tool"),
    targets = getList("targets", String::class.java),
    clientId = getString("clientId"),
    detail = getString("detail"),
    expiresAt = getDate("expiresAt") ?: Date(),
  )
}
