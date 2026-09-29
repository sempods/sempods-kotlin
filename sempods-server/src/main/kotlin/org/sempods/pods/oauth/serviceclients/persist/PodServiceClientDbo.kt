package org.sempods.pods.oauth.serviceclients.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * A service client: a client acting as itself through `client_credentials`, with no person behind
 * its tokens.
 *
 * An operator provisions one through the host admin surface, or a service registers itself at
 * `POST {pod}/_system/auth/register` and waits for the owner to activate it ([pendingUntil]); see
 * `sempods-server/docs/auth/service-clients.md`. Public clients registered there live in
 * [org.sempods.pods.oauth.DynamicClientRegistrationDbo]. Each row carries a bcrypt hash of the
 * shared secret; the plaintext never persists here.
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
   * The context grants the client holds, stored under the field name `scopes`.
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

  /**
   * The redirect URIs the service registered; empty where it registered none. The service consent
   * returns only to one of these (`PodServiceConsentFlow`).
   */
  val redirectUris: List<String> = emptyList(),

  /**
   * Until when a self-registered service waits for the owner's consent; `null` once activated, and
   * on every operator-provisioned row. A TTL index removes the row after it, and every read treats
   * a passed deadline as absent, because the TTL monitor lags. Activation removes it in the same
   * update that writes the grants (`PodServiceClientDao.replaceScopes`).
   */
  val pendingUntil: Instant? = null,
)
