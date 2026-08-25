package org.sempods.pods.media

import org.sempods.pods.PodId

/**
 * What identifies one media object: the pod that owns it, and the content hash that names it.
 *
 * This pair, and never a storage key, is how the pod server addresses bytes. A
 * [PodMediaStore] turns it into whatever its backend needs — a path, an object key, a bucket and a
 * name — and nobody outside that implementation knows or needs to know which. Two consequences
 * worth stating, because both are the reason the pair exists:
 *
 * - **A store owns its physical layout.** A filesystem store may shard directories by hash prefix
 *   and an object store may not, without any agreement between them and without a stored key that
 *   would have pinned one layout for everyone.
 * - **Nothing above a store may compute a location.** The registry stores this pair and no key, so
 *   moving a deployment from the filesystem store to an S3 one changes a variable rather than a
 *   column. That the two shipped implementations happen to agree on `{podId}/{mediaId}` — each says
 *   so in its own KDoc — is what makes such a move an `rclone sync`; it is their statement, not a
 *   property of this type.
 *
 * [podId] is a [PodId] and not this deployment's row key: the sentence above was only half true
 * while it was a MongoDB `ObjectId`, since a store implementing this seam then had to speak one.
 * The translation lives in `pods/mongo/persist/PodIds.kt` and the token is unchanged, so nothing on
 * disk moved when it was made true.
 *
 * [podId] first because it is the ownership boundary: no operation crosses it, and every listing is
 * either scoped to one pod or explicitly to all of them.
 */
data class PodMediaRef(
  val podId: PodId,
  val mediaId: String,
)
