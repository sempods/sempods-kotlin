package org.sempods.mcp.persist

import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
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
  /**
   * The client_id the service registered at the pod via DCR. It seeds the next re-authorize, which
   * presents it again so the pod pre-checks the grants held under it; a refresh presents
   * [PodTokens.podClientId] instead, because that row and this one are written separately and can
   * disagree.
   */
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
  val createdAt: Date,
  val updatedAt: Date,
  /**
   * The redirect URI [podClientId] is pinned to at the pod, so presenting that id again means
   * presenting this address. Null is a connection made while every profile shared one client, and
   * means the parent `…/_system/ui/pods/callback`; `PodClientIdentity.profileOf` reads it back.
   *
   * Last in the list so every existing `componentN()` keeps its meaning — a consumer compiled
   * against an older artifact then fails to link rather than destructuring this where it asked for
   * the scopes. The constructor and `copy` change either way, which this module's promise does not
   * cover: that is the embedding contract, per `org.sempods.probe.auth.embedSempodsAuth`.
   */
  val podRedirectUri: String? = null,
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
 *   collection of its own, for the reason `sempods-commons-mongo/docs/document-contract.md` §"Conventions" states.
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
    putNotNull("podRedirectUri", podRedirectUri)
    put("scopes", scopes.toList())
    put("podSubject", podSubject)
    put("subjectVerified", subjectVerified)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
  }

  private fun Document.toConnection() = PodConnection(
    user = getString("user"),
    profile = getString("profile"),
    pod = getString("pod"),
    issuer = getString("issuer"),
    podClientId = getString("podClientId"),
    podRedirectUri = getString("podRedirectUri"),
    scopes = (getList("scopes", String::class.java) ?: emptyList()).toSet(),
    podSubject = getString("podSubject"),
    subjectVerified = getBoolean("subjectVerified", false),
    createdAt = getDate("createdAt") ?: Date(),
    updatedAt = getDate("updatedAt") ?: Date(),
  )
}
