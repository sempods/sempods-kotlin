package org.sempods.pods.oauth.serviceclients.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * A service client: a client acting as itself through `client_credentials`, with no person behind
 * its tokens.
 *
 * Service clients are NOT created via RFC 7591 Dynamic Client Registration
 * (those live in [org.sempods.pods.oauth.DynamicClientRegistrationDbo]).
 * An operator provisions one through the host admin surface, or a pod owner installs one; see
 * `docs/auth/service-clients.md`. Each row carries a bcrypt hash of the shared secret; the
 * plaintext never persists here.
 *
 * Its grants live on this row, in [scopes], and nowhere else: the resolver reads them per request,
 * and a single-document update is what makes a replace of them optimistic ([grantsVersion]).
 *
 * A plain data class: the collection name, the unique index and the mapping onto a BSON document
 * live in [PodServiceClientDao], which talks to the driver.
 *
 * **The declaration order is the insert order**: `PodServiceClientDao.toDocument` writes the fields
 * in exactly this sequence. Fields set later by an update are appended after the ones already
 * there, in an order the server picks, so rows differ in order where their histories do.
 */
internal data class PodServiceClientDbo(
  val id: ObjectId? = null,

  val podId: ObjectId,

  val clientId: String,

  /** bcrypt hash of the shared secret. Plaintext is never persisted. */
  val secretHash: String,

  /**
   * The context grants the client holds. The BSON field keeps its old name.
   *
   * May be empty, in either of two spellings: absent on a row inserted without grants and never
   * changed since, and `[]` on one an update emptied. Both read back as an empty set, and what such
   * a registration is worth is `PodServiceClientStore.register`'s.
   */
  val scopes: Set<String>,

  /** Human-readable label, e.g. the application's name. Free-form, for operator-side identification. */
  val label: String? = null,

  val createdAt: Instant = Instant.now(),

  /** Touched on every successful token issuance — feeds operator observability. */
  val lastUsedAt: Instant? = null,

  /**
   * How often [scopes] have been written since the row was inserted. **Every write to [scopes]
   * increments it**, so a replace prepared at one version writes nothing once anything else changed
   * them (`PodGrantsFacade.replaceGrants`). Absent until the first write; see
   * `sempods-server/docs/collections.md`. A registration re-created under the same `clientId`
   * starts again at `0`.
   */
  val grantsVersion: Long = 0,

  /** When a person last changed [scopes]. A change the server makes moves [grantsVersion] alone. */
  val grantsChangedAt: Instant? = null,

  /** The WebID behind [grantsChangedAt]. */
  val grantsChangedBy: String? = null,
)
