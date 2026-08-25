package org.sempods.pods

/**
 * A pod's identity within one deployment — the token everything keyed per pod is keyed by.
 *
 * **Opaque.** A non-empty token of at most 64 characters from `A-Z a-z 0-9 - _`, which is a safe
 * filesystem path segment and a safe object-store key segment. A deployment mints them; nothing
 * above a store reads meaning into one, and no store may assume more than that shape.
 *
 * Distinct from [org.sempods.spec.PodRef], which carries a pod's public identity — its URI, owner
 * and label. A pod id outlives the pod: [org.sempods.pods.media.PodMediaStore.iterate] reaches pods
 * that no longer exist, and those have no URI and no owner left to name them by.
 *
 * A value class because a [org.sempods.pods.media.PodMediaRef] is two identifying strings side by
 * side, and untyped they would swap without a compiler complaint.
 */
@JvmInline
value class PodId(val value: String) : Comparable<PodId> {

  init {
    require(isValid(value)) { "not a pod id: '$value'" }
  }

  override fun compareTo(other: PodId): Int = value.compareTo(other.value)

  /** The token itself, so a log line reads as the id rather than as a wrapper. */
  override fun toString(): String = value

  companion object {

    /**
     * [raw] as a [PodId], or `null` when it is not one.
     *
     * For reading an id back out of somewhere that holds foreign strings too — a directory under a
     * media root, a key prefix in a bucket. Answers rather than throws, so a store can skip what is
     * not its own.
     */
    fun parseOrNull(raw: String): PodId? = if (isValid(raw)) PodId(raw) else null

    /** Long enough for any minting scheme, short enough never to be why a path or key hits a limit. */
    private const val MAX_LENGTH = 64

    private fun isValid(raw: String): Boolean =
      raw.isNotEmpty() && raw.length <= MAX_LENGTH && raw.all { it.isSafeInAPathOrKey() }

    private fun Char.isSafeInAPathOrKey(): Boolean =
      this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
  }
}
