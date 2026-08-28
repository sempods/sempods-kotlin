package org.sempods.pods.grants.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * An **owner-level, app-independent** grant: a WebID may exercise [scope] on this pod,
 * regardless of which app it authenticates through. This is the row `SPS-GRANT-012` requires —
 * a person's grants, explicit and stored per pod and per identity, keyed `(podId, webId, scope)`.
 * It is the left-hand side of the intersection in `SPS-GRANT-013`, not the intersection itself.
 *
 * Distinct from [PodGrantDbo] (`grants`), which is app-*delegated* — keyed
 * additionally by `appId` and written by the OAuth consent flow to record what a user
 * delegated to a specific app (`granted = requested ∩ user_grants`). These grants feed the
 * intersection's `user_grants` side: [org.sempods.pods.grants.PodGrantsFacade]'s
 * `resolveUserGrants` reads them so a non-owner WebID the owner granted access to can
 * actually delegate those contexts at consent time (before this, non-owners resolved to an
 * empty set and could obtain no token).
 *
 * Because the request path never reads this collection, a row removed here does not by itself
 * reach an app that already consented — [org.sempods.pods.grants.PodGrantsFacade] cascades the
 * change into [PodGrantDbo]. Never write this store directly.
 *
 * [scope] follows the grant-string grammar `<context-iri>#read|write|manage`
 * (`PodScopeValidator`).
 *
 * A plain data class: the collection name, the unique index and the mapping onto a BSON document
 * live in [PodWebIdGrantsDao], which talks to the driver. There is no no-arg constructor either —
 * it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels
 * were values no reader ever saw.
 *
 * **The declaration order is the wire order**, but as for [PodGrantDbo] nothing here is written by
 * a document mapper: rows are created by an upsert, and Mongo lays the inserted document out from
 * the query's equality fields plus `$setOnInsert`. [PodWebIdGrantsDao.addGrants] is where that
 * order is actually decided.
 */
internal data class PodWebIdGrantDbo(
  val id: ObjectId? = null,
  val podId: ObjectId,
  val webId: String,
  val scope: String,
  val grantedBy: String?,
  val grantedAt: Instant = Instant.now(),
)
