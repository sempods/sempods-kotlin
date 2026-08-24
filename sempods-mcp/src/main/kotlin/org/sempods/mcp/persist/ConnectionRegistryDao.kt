package org.sempods.mcp.persist

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Updates
import org.bson.Document
import org.sempods.commons.mongo.putNotNull
import java.util.Date
import org.sempods.mcp.SempodsMcpCollections

/**
 * One row per connected pod, keyed `(user, profile, pod)`. Records what the service knows
 * about a pod connection: the pod's OAuth issuer, the DCR client the service registered at
 * that pod, and the granted scopes. The actual pod tokens live in [TokenVaultDao].
 *
 * M1 establishes the schema; rows are written from M2 (connect-a-pod) onward.
 */
data class PodConnection(
  val user: String,
  val profile: String,
  val pod: String,
  /** The pod's OAuth authorization-server issuer, discovered via the pod's metadata. */
  val issuer: String,
  /** The client_id the service registered at the pod via DCR. */
  val podClientId: String,
  /** Feature scopes granted to the service by the pod (e.g. "public-read"). */
  val scopes: Set<String>,
  /**
   * The WebID the pod itself minted as the token's `sub` — the pod-local identity of the person
   * this connection acts as. May differ from [user] (the id.sempods.org identity the caller signed
   * into the service as) when the pod runs its own identity provider: the connection stays keyed
   * under [user], but every use acts on the pod as [podSubject]. Null only for legacy rows written
   * before this was captured.
   */
  val podSubject: String? = null,
  /**
   * Whether [podSubject] was cryptographically verified against the pod's JWKS. False = the pod
   * exposes no JWKS and `sub` was decoded from the token the service fetched directly from the
   * pod's token endpoint over TLS (trusted by transport, not by signature).
   */
  val subjectVerified: Boolean = false,
  /**
   * When the pod last answered a refresh with `invalid_grant` — the one code that means the grant
   * is finished (RFC 6749 §5.2), not that the attempt failed.
   *
   * Set so the dashboard can say "reconnect needed" instead of showing the pod as healthy while
   * every tool call quietly returns no token. Cleared by a successful (re-)connect, which writes a
   * fresh row. The row itself is **never** deleted on a dead grant: it carries the pod URL the
   * person needs in order to reconnect.
   */
  val deadGrantSince: Date? = null,
  val createdAt: Date,
  val updatedAt: Date,
) {
  /** True when the pod authorized a different WebID than the service identity ([user]). */
  val foreignIdentity: Boolean get() = podSubject != null && podSubject != user

  /**
   * Stamp a per-pod tool envelope (read fan-out entry or write result) with the foreign-identity
   * markers when this connection acts on the pod as its own [podSubject]. One place so the read and
   * write surfaces cannot drift. No-op for a same-identity connection.
   *
   * `similar_to` names the caller's sempods WebID ([user]) that [podSubject] *likely* denotes the
   * same person as — a **weak** hint (think `rdfs:seeAlso` / "similar"), deliberately NOT an asserted
   * `owl:sameAs`. It lets a client correlate the two WebIDs in a graph without collapsing them.
   */
  fun annotateForeignIdentity(envelope: MutableMap<String, Any?>) {
    if (foreignIdentity) {
      envelope["foreign_identity"] = true
      envelope["pod_subject"] = podSubject
      envelope["similar_to"] = user
    }
  }
}

/**
 * @param collectionName the production name is the default; a test points an instance at a
 *   collection of its own, for the reason `docs/persistence.md` §"Conventions" states.
 */
class ConnectionRegistryDao(
  db: MongoDatabase,
  collectionName: String = SempodsMcpCollections.CONNECTIONS,
) {

  private val connections = db.getCollection(collectionName)

  init {
    connections.createIndex(
      Indexes.ascending("user", "profile", "pod"),
      IndexOptions().unique(true),
    )
  }

  fun find(key: PodKey): PodConnection? =
    connections.find(keyFilter(key)).firstOrNull()?.toConnection()

  fun listForProfile(profile: ProfileKey): List<PodConnection> =
    connections.find(
      Filters.and(
        Filters.eq("user", profile.user),
        Filters.eq("profile", profile.profile),
      ),
    ).map { it.toConnection() }.toList()

  fun upsert(connection: PodConnection) {
    connections.replaceOne(
      keyFilter(PodKey(connection.user, connection.profile, connection.pod)),
      connection.toDocument(),
      ReplaceOptions().upsert(true),
    )
  }

  /**
   * Records that the pod declared this connection's grant finished.
   *
   * Compare-and-set on [PodConnection.updatedAt] rather than a blind update, because the decision
   * is made *after* a network round trip: a reconnect that landed in the meantime would otherwise
   * be stamped "reconnect needed" permanently, and nothing clears the mark except another
   * reconnect. It also makes this a no-op rather than a resurrection when a disconnect deleted the
   * row mid-flight. Same reasoning as `TokenVaultDao.replaceIfClaimedBy` on the token side.
   *
   * @return whether the row was still the one that was read.
   */
  fun markDeadGrant(key: PodKey, at: Date, ifUpdatedAt: Date): Boolean =
    connections.updateOne(
      Filters.and(keyFilter(key), Filters.eq("updatedAt", ifUpdatedAt)),
      Updates.combine(Updates.set("deadGrantSince", at), Updates.set("updatedAt", at)),
    ).modifiedCount == 1L

  fun delete(key: PodKey) {
    connections.deleteOne(keyFilter(key))
  }

  private fun keyFilter(key: PodKey) = Filters.and(
    Filters.eq("user", key.user),
    Filters.eq("profile", key.profile),
    Filters.eq("pod", key.pod),
  )

  private fun PodConnection.toDocument() = Document().apply {
    put("user", user)
    put("profile", profile)
    put("pod", pod)
    put("issuer", issuer)
    put("podClientId", podClientId)
    put("scopes", scopes.toList())
    put("podSubject", podSubject)
    put("subjectVerified", subjectVerified)
    // `putNotNull`, unlike the `podSubject` line above: an absent field is the contract
    // `docs/persistence.md` states, and it is the common case here.
    putNotNull("deadGrantSince", deadGrantSince)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
  }

  private fun Document.toConnection() = PodConnection(
    user = getString("user"),
    profile = getString("profile"),
    pod = getString("pod"),
    issuer = getString("issuer"),
    podClientId = getString("podClientId"),
    scopes = (getList("scopes", String::class.java) ?: emptyList()).toSet(),
    podSubject = getString("podSubject"),
    subjectVerified = getBoolean("subjectVerified", false),
    deadGrantSince = getDate("deadGrantSince"),
    createdAt = getDate("createdAt") ?: Date(),
    updatedAt = getDate("updatedAt") ?: Date(),
  )
}
