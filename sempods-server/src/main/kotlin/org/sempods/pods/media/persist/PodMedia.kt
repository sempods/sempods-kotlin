package org.sempods.pods.media.persist

import org.sempods.pods.media.PodMediaRef
import org.sempods.pods.mongo.persist.toPodId
import org.bson.types.ObjectId
import java.net.URI
import java.time.Instant

/**
 * One media object of one pod, as the registry knows it — the control-plane row beside the bytes in
 * `org.sempods.pods.media.PodMediaStore`.
 *
 * The registry is the authority on everything *about* the object; the store holds only bytes. That
 * split is why a deployment without a blob store still has a registry: the lifecycle cascades on
 * context and pod deletion are registry work and must keep running — see `docs/media.md`
 * §"The seam".
 *
 * **The row carries only what the bytes themselves determine.** Everything descriptive — a
 * filename, a claimed content type, who put it there and when — lives per [assignments] entry, and
 * that is a confidentiality decision rather than a modelling preference. See [MediaAssignment].
 *
 * @property mediaId the base64url SHA-256 of the bytes, and the `{id}` in
 *   `{pod}/_system/media/{id}/content`. Content-addressed, so re-uploading the same bytes yields
 *   the same id and the same URL.
 * @property assignments **the reference count.** A media is readable exactly when one of these
 *   names a context the caller may read, and it becomes a deletion candidate when the set empties.
 * @property unreferencedSince when [assignments] last became empty, `null` while it is not. The
 *   sweep reads it; [PodMediaDao] maintains it atomically, because deriving it in the caller
 *   would leave a window in which a row is unreferenced and does not say so.
 */
data class PodMedia(
  val podId: ObjectId,
  val mediaId: String,
  val size: Long,
  val assignments: Set<MediaAssignment>,
  val unreferencedSince: Instant?,
) {

  /**
   * How the store addresses this object's bytes.
   *
   * Derived, never stored: the row used to carry a `storageKey`, which was always
   * `{podId}/{mediaId}` and therefore said nothing the two fields did not — while quietly pinning
   * one physical layout for every implementation. A store owns its own layout now; see
   * [PodMediaRef].
   *
   * This is also where the row's key becomes the seam's [org.sempods.pods.PodId] — one conversion,
   * at the edge, in the direction `docs/modularity.md` §"The pattern" describes.
   */
  val ref: PodMediaRef get() = PodMediaRef(podId.toPodId(), mediaId)

  /** The context URIs this media is assigned to. */
  val contexts: Set<String> get() = assignments.mapTo(HashSet(), MediaAssignment::context)

  /** The assignments [readableContexts] covers; `null` there means an unrestricted caller. */
  fun readableBy(readableContexts: Set<URI>?): Set<MediaAssignment> {
    if (readableContexts == null) return assignments
    val readable = readableContexts.mapTo(HashSet(), URI::toString)
    return assignments.filterTo(HashSet()) { it.context in readable }
  }
}

/**
 * One context's claim on a media object: that it belongs there, and what whoever put it there said
 * about it.
 *
 * **The descriptive fields sit here rather than on the row because a media object can be reached by
 * two parties who may not see each other.** Content addressing means the second upload of identical
 * bytes finds the first one's object and adds an assignment; if the description were shared, that
 * second uploader would read back a `contentType`, `filename` and `createdAt` they never supplied —
 * and could tell, by comparing them with what they sent, that the pod already held the file. For
 * someone holding write on a single sandbox context, that turns the upload route into an oracle
 * over every *other* app's private contexts, which is exactly the isolation contexts exist to give.
 *
 * So each assignment carries its own description, every reader sees the ones they may read, and the
 * bytes are still stored once.
 *
 * @property contentType what the assigning party called it. A claim, not a measurement — two
 *   contexts may legitimately disagree about the same bytes, and the content route answers with the
 *   claim made in a context the reader can see.
 */
data class MediaAssignment(
  val context: String,
  val contentType: String,
  val filename: String?,
  val createdAt: Instant,
  val createdBy: String?,
)
