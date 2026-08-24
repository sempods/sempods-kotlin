package org.sempods.mcp.persist

/**
 * The canonical key for everything connection- and token-related: `(user, profile, pod)`.
 *
 * - [user] is the stable WebID (sempods-auth `sub`) — the root of the key.
 * - [profile] is the isolation bundle; there is always an implicit [DEFAULT_PROFILE].
 * - [pod] is the pod base URL (the target).
 *
 * Keeping the profile in the key from day one is what makes profiles a real isolation
 * boundary later rather than a relabelling of a shared token pool.
 */
data class PodKey(
  val user: String,
  val profile: String,
  val pod: String,
) {
  companion object {
    const val DEFAULT_PROFILE = "default"
  }
}

/** The `(user, profile)` identity without a specific pod — a service session / connection bundle. */
data class ProfileKey(
  val user: String,
  val profile: String,
) {
  companion object {
    const val DEFAULT_PROFILE = PodKey.DEFAULT_PROFILE
  }
}
