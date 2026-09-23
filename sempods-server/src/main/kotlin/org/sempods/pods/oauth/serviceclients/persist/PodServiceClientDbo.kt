package org.sempods.pods.oauth.serviceclients.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Statically-registered OAuth 2-leg (`client_credentials`) client. Used by trusted service apps
 * that act on the pod owner's behalf without presenting a WebID.
 *
 * Service clients are NOT created via RFC 7591 Dynamic Client Registration
 * (those live in [org.sempods.pods.oauth.DynamicClientRegistrationDbo]).
 * The row is inserted by either an admin script or by the
 * bootstrap step that runs on pod creation (see
 * `docs/auth/service-clients.md`). Each row
 * carries a bcrypt hash of the shared secret; the plaintext never persists
 * here.
 *
 * Scope: the [scopes] set lists exactly what the client may request at the
 * token endpoint. For a provisioned app this is a single `<app-root>#manage`
 * scope which — via the slash-delimited manage semantics enforced at
 * `PodResourceWriteService.kt:217–223` — sandboxes the client to its own
 * sub-tree without introducing any new scope type.
 *
 * A plain data class: the collection name, the unique index and the mapping onto a BSON document
 * live in [PodServiceClientDao], which talks to the driver. There is no no-arg constructor either
 * — it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels
 * were values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: it is what a row already on disk
 * carries, and `PodServiceClientDao.toDocument` writes the fields in exactly this sequence.
 */
internal data class PodServiceClientDbo(
  val id: ObjectId? = null,

  val podId: ObjectId,

  val clientId: String,

  /** bcrypt hash of the shared secret. Plaintext is never persisted. */
  val secretHash: String,

  /**
   * Scopes the client is allowed to request at the token endpoint. The token
   * endpoint refuses to issue scopes outside this set.
   *
   * May be empty, in either of two spellings: absent on a row inserted without scopes, and `[]`
   * on one [PodServiceClientDao.revokeByContextScope] emptied. Both read back as an empty set, and
   * what such a registration is worth is `PodServiceClientStore.register`'s.
   */
  val scopes: Set<String>,

  /** Human-readable label, e.g. the application's name. Free-form, for operator-side identification. */
  val label: String? = null,

  val createdAt: Instant = Instant.now(),

  /** Touched on every successful token issuance — feeds operator observability. */
  val lastUsedAt: Instant? = null,
)
