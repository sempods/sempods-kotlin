package org.sempods.pods.grants.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * One app-delegated grant, keyed `(podId, appId, webId, scope)`.
 *
 * A plain data class: the collection name, the three indexes and the mapping onto a BSON document
 * live in [PodGrantsDao], which talks to the driver. There is no no-arg constructor either — it
 * existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels were
 * values no reader ever saw.
 *
 * **The declaration order is the wire order**, but unlike the other migrated entities nothing here
 * is written by a document mapper: rows are created by an upsert, and Mongo lays the inserted
 * document out from the query's equality fields plus `$setOnInsert`. [PodGrantsDao.addGrants] is
 * where that order is actually decided.
 */
data class PodGrantDbo(
  val id: ObjectId? = null,
  val podId: ObjectId,
  val appId: String,
  val webId: String,
  val scope: String,
  /**
   * Every identity URI the consenting person was known by at consent time — `identity.allUris`
   * (their WebID plus `also_known_as`). [webId] alone is not enough for revocation: a pod owner
   * may have written the owner-level grant ([PodWebIdGrantDbo]) under an alias URI, while this
   * row always carries the *primary* WebID. `also_known_as` lives in sempods-auth (separate
   * service, separate database) and cannot be resolved pod-side, so the alias set is recorded
   * here at the one moment it is known.
   *
   * `null` on rows written before this field existed. Treat that as `listOf(webId)` — exactly
   * the pre-existing behaviour — so no backfill is needed.
   */
  val subjectUris: List<String>? = null,
  val grantedBy: String?,
  val grantedAt: Instant = Instant.now(),
)
