package org.sempods.pods.media

import com.google.inject.Inject
import org.sempods.pods.media.persist.PodMedia
import org.sempods.pods.media.persist.PodMediaDao
import org.sempods.pods.mongo.persist.PodDao

/**
 * Reads the media registry **by pod name**, for tests outside `:sempods-server` that assert on what
 * the pod recorded about an upload.
 *
 * A fixture rather than something a test writes for itself, because the translation it does is the
 * one a consumer cannot do: [PodMediaDao] is keyed by `(podId, mediaId)`, and a pod's `ObjectId` is
 * deliberately not part of anything sempods publishes — a consumer knows pods by name and has no
 * business knowing more. `PodDao.fetchByName` is `internal`, so this lives here, on the inside.
 *
 * Scoping by pod is not a convenience either, it is what makes the assertions true: a media id is
 * the SHA-256 of the content, so the same test image uploaded into two pods is the same id in two
 * rows. A lookup that only matched the id would answer with whichever pod's row came first — a test
 * that passes for the wrong reason, and one that starts failing when an unrelated test is added.
 */
class PodMediaTestAccess @Inject constructor(
  private val podDao: PodDao,
  private val mediaDao: PodMediaDao,
) {

  /** [pod]'s row for [mediaId], or `null` if that pod holds no such media. */
  fun find(pod: String, mediaId: String): PodMedia? =
    podId(pod)?.let { mediaDao.find(it, mediaId) }

  /** Every media row of [pod], assigned or not. */
  fun findAll(pod: String): List<PodMedia> =
    podId(pod)?.let { podId -> mediaDao.iterate(podId) { rows -> rows.toList() } }.orEmpty()

  private fun podId(pod: String) = podDao.fetchByName(pod)?.id
}
