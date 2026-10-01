package org.sempods.pods.oauth.spi

/** The outcome of [PodRequestVerifier.verify]. */
sealed interface PodTokenAuthentication {

  /** No bearer was presented. An anonymous caller, which is a legitimate state, not a failure. */
  data object NoToken : PodTokenAuthentication

  /** The bearer verified; [token] carries what it claims. */
  data class Verified(val token: PodAccessToken) : PodTokenAuthentication

  /** A bearer was presented and is not usable. [reason] is why, not what to answer. */
  data class Rejected(val reason: PodTokenRejection) : PodTokenAuthentication
}

/**
 * Why a presented bearer was refused. Kept apart from the HTTP status deliberately:
 * [podMismatch] is the one an endpoint may want to distinguish (a token that is valid, but for
 * another pod), and the two read paths answer it differently.
 */
enum class PodTokenRejection {
  /** Unparseable, wrongly signed, expired, or a user token without a `sub`. */
  invalidToken,

  /** Verified, but carries no `client_id` — no app to resolve grants for. */
  missingClientId,

  /** Verified, but issued by (and for) a different pod. */
  podMismatch,
}
