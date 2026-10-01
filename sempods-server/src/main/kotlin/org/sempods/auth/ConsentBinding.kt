package org.sempods.auth

/**
 * What the screen was rendered for, as normalized by `/authorize`.
 *
 * @param clientId the recipient, and [redirectUri] where its answer goes; `null` only on a service
 *   consent opened without one. The submission answers this client and no other.
 * @param state the client's `state`, `null` where it sent none.
 * @param codeChallenge the PKCE challenge the code will carry, and [codeChallengeMethod] its
 *   method; `null` where the request carried none.
 * @param offeredContexts the IRI of every context row the screen rendered, each with its
 *   `read`, `write` and `manage` boxes. The privileged feature scopes are
 *   [ConsentTransactionStore.Transaction.offeredFeatureScopes].
 * @param publicReadOffered whether the screen rendered the `public-read` box.
 * @param contextCreationOffered whether the screen let the person create contexts.
 * @param service set on a service consent, `null` on a delegated one. Each submission route
 *   refuses the other's screen.
 */
data class ConsentBinding @JvmOverloads constructor(
  val clientId: String,
  val redirectUri: String?,
  val state: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  val offeredContexts: Set<String>,
  val publicReadOffered: Boolean,
  val contextCreationOffered: Boolean,
  val service: ConsentServiceRecipient? = null,
)

/**
 * The service registration a service consent was rendered for.
 *
 * @param registrationId the registration's own id, so a registration removed and re-created under
 *   the same `client_id` is not the one approved.
 * @param grantsVersion its grants' version when the screen was rendered. The replace writes only
 *   at this version.
 */
data class ConsentServiceRecipient(val registrationId: String, val grantsVersion: Long)
