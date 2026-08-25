package org.sempods.pods

/**
 * A pod's identity **within one deployment** — the token everything keyed per pod is keyed by, and
 * nothing else.
 *
 * This is the second half of what [org.sempods.spec.PodRef] started. `PodRef` took the stored row
 * out of the seams and carries a pod's *public* identity (its URI, its owner, the host-relative
 * name); what stayed behind was the storage **key type**, and a seam typed in `ObjectId` still
 * required a deployment bringing a different store to speak MongoDB. `docs/modularity.md`
 * §"The pattern" states the rule both types serve.
 *
 * **Why not `PodRef` here.** [PodMediaStore.iterate][org.sempods.pods.media.PodMediaStore.iterate]
 * with no scope must include pods that no longer exist — that is the case the parameter exists for,
 * since a pod deleted inside its grace period still has bytes. There is no `PodRef` for such a pod:
 * no URI to mint, no owner to carry. An opaque token is the only identity that outlives the row.
 *
 * **Opaque, and deliberately so.** Nothing above a store may read meaning into a pod id, and no
 * store may assume a format beyond what [value] promises: a non-empty token of at most 64
 * characters from `A-Z a-z 0-9 - _`. That is a safe filesystem path segment and a safe object-store
 * key segment, which is the whole of what the two shipped implementations need. This reference
 * implementation happens to mint the 24-character hex of a MongoDB `ObjectId` — a statement of
 * `pods/mongo/persist/PodIds.kt`, not a property of this type, and the reason the on-disk and
 * in-bucket layouts did not change when this type replaced `ObjectId` in the seam.
 *
 * A value class rather than a bare `String`, and the first one in this codebase: a
 * [org.sempods.pods.media.PodMediaRef] is two identifying strings side by side, and untyped they
 * would swap without a compiler complaint.
 */
@JvmInline
value class PodId(val value: String) : Comparable<PodId> {

  init {
    require(isValid(value)) { "not a pod id: '$value'" }
  }

  override fun compareTo(other: PodId): Int = value.compareTo(other.value)

  /** The token itself, so a log line or an error message reads as the id rather than as a wrapper. */
  override fun toString(): String = value

  companion object {

    /**
     * [raw] as a [PodId], or `null` when it is not one.
     *
     * For reading an id back out of a place that holds strings and may hold foreign ones too — a
     * directory under a media root, a key prefix in a bucket. A store uses it to skip what is not
     * its own instead of guessing, which is why this answers `null` rather than throwing.
     */
    fun parseOrNull(raw: String): PodId? = if (isValid(raw)) PodId(raw) else null

    /**
     * Long enough for anything a deployment would mint (the reference implementation uses 24), short
     * enough that a pod id can never be the reason a path or a key hits a backend's own limit.
     */
    private const val MAX_LENGTH = 64

    private fun isValid(raw: String): Boolean =
      raw.isNotEmpty() && raw.length <= MAX_LENGTH && raw.all { it.isSafeInAPathOrKey() }

    private fun Char.isSafeInAPathOrKey(): Boolean =
      this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
  }
}
