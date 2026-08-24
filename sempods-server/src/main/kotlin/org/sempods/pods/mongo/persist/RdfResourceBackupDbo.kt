package org.sempods.pods.mongo.persist

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Minimal recovery backup of a resource **per context**: the resource's statements in one named
 * graph, serialized as N-Quads. One document per `(podId, resourceUri, context)`.
 *
 * Context-grained because context is the first-class unit in sempods (authorization, lifecycle):
 * this lets recovery restore a single context cheaply, not only the whole pod. It is sound because
 * **blank nodes are forbidden** — a resource's statements in a context are self-contained, so they
 * never reach into another context's document. No derived fields beyond the context key; the
 * collection exists solely to reconstruct the MemoryStore (whole-pod or per-context).
 *
 * A plain data class: the collection name, the two indexes and the mapping onto a BSON document
 * live in [RdfResourceBackupDao], which talks to the driver. There is no no-arg constructor either
 * — it existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels
 * were values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: it is what a row already on disk
 * carries, and `RdfResourceBackupDao.toDocument` writes the fields in exactly this sequence.
 */
data class RdfResourceBackupDbo(

  val id: ObjectId? = null,

  val podId: ObjectId,

  val resourceUri: String,

  val context: String,

  /**
   * The resource's statements in [context], as N-Quads. The largest field in the database, and
   * the reason this collection exists.
   *
   * Empty is a value rather than an absence — it says the resource has no statements left in this
   * context — so it is stored as `""` and not omitted the way a null field would be.
   */
  val nquads: String = "",

  val updatedAt: Instant = Instant.now(),
)
