package org.sempods.pods.mongo.persist

import org.bson.types.ObjectId
import org.sempods.SempodsUriBuilder
import org.sempods.spec.PodRef
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One pod. Its [id] is the `podId` every other collection in the server is keyed by.
 *
 * A plain data class: the collection name, the unique index on [name] and the mapping onto a BSON
 * document live in [PodDao], which talks to the driver. There is no no-arg constructor either — it
 * existed only so Morphia's `PojoCodec` had an entry point, and its `MorphiaUtil` sentinels were
 * values no reader ever saw.
 *
 * **The declaration order is the wire order** and is not free: it is what a row already on disk
 * carries, and `PodDao.toDocument` writes the fields in exactly this sequence.
 */
data class PodDbo(

  val id: ObjectId? = null,

  val name: String,

  /** WebID URI of the pod owner (e.g. `https://id.sempods.org/e/<sha256>`). */
  val owner: String,

  /**
   * Human-readable label for the pod. Surfaced as `name` in the RFC 9728
   * protected-resource metadata so SDK consumers can render a sensible
   * `PodConnection.displayName` without falling back to the hostname.
   * Optional — null means clients should derive a label themselves.
   *
   * Null and empty are different states on the wire: a row that never carried a label has no
   * field at all, while clearing one stores `""`.
   */
  val displayName: String? = null,

  val lastModifiedAt: Instant = Instant.now().truncatedTo(ChronoUnit.DAYS),

  /**
   * How many `resources` rows the write path believes this pod has — one per
   * `(resource, context)`, the granularity `RdfResourceBackupDao` stores at, not a count of
   * resources. Maintained by `BackupSinkPodChangeListener` as rows appear and vanish, and compared
   * by `PodRepositoryCache` against what a recovery actually loaded: an empty recovery against a
   * count of fifty is durable state gone missing, while an empty recovery against a count of zero is
   * a pod nothing was written to, or one whose owner deleted its last resource. Reading that off the
   * current state cannot tell the two apart, which is why the number is written at the transition.
   *
   * Null for a row that predates the field, and only for that — every pod registered since carries a
   * count from its first moment. A recovery establishes the baseline for such a row from what it
   * loaded and the pod is tracked from then on, so nothing has to be backfilled.
   */
  val resourceRowCount: Long? = null,
)

/**
 * The stored row as the rest of the server sees a pod, with this deployment's address applied so
 * the result carries the pod's own URI rather than a name that means nothing without it.
 *
 * Drops [PodDbo.id]: that is this implementation's storage key, and it has no place in a signature
 * a deployment could supply its own implementation for — see `docs/concepts/modularity.md`
 * §"The pattern". Code that needs the key back resolves it from [PodDbo.name] through
 * `PodFacade.getPodId`, which is cached. The edge runs this way only: `sempods-model` knows nothing
 * about this class.
 */
fun PodDbo.toRef(uriBuilder: SempodsUriBuilder): PodRef = PodRef(
  uri = uriBuilder.buildPodUri(name),
  name = name,
  owner = owner,
  displayName = displayName,
)
