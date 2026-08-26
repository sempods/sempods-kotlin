package org.sempods.pods.contexts.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * One row per registered context of a pod, keyed `(podId, contextUri)`.
 *
 * A plain data class: the collection name, the two indexes and the mapping onto a BSON document
 * live in [PodContextsDao], which talks to the driver. There is no no-arg constructor either — it
 * existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels were
 * values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: it is what a row already on disk
 * carries, and `PodContextsDao.toDocument` writes the fields in exactly this sequence.
 */
internal data class PodContextDbo(
  val id: ObjectId? = null,
  val podId: ObjectId,
  val contextUri: String,
  val label: String?,
  val description: String?,
  /**
   * Single source of truth for context visibility. `true` makes the context
   * anonymously readable and lets it surface in `SempodsPodClient.publicContexts`.
   * Defaults to `false`; legacy rows without the field decode as private, which the
   * public-contexts backfill corrects for the rows written before the field existed.
   *
   * Written even when `false` — unlike a null or an empty collection, a boolean carries
   * information either way, and `fetchByPod(public = false)` filters on the stored value.
   */
  val isPublic: Boolean = false,
  val createdAt: Instant = Instant.now(),
  val createdBy: String?,
)
