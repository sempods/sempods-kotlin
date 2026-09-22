package org.sempods.pods.oauth.flows

import org.sempods.pods.oauth.PodRefreshTokenStore

/**
 * What this authorization made of this person, this client and this pod — everything the consent
 * dialog shows, decided. The presentation around it is `PodAuthorizeResponses`'.
 *
 * @param clientName what to call the client — its registered name where it has one, its `client_id`
 *   otherwise, so the dialog never shows a blank.
 * @param clientUri where the client says it lives, and [logoUri] its logo; `null` where it named
 *   none or named one `ClientMetadataUri` refuses.
 * @param state the client's `state`, `null` where it sent none — the form posts it back unchanged.
 * @param csrfToken this screen's one-time ticket — see where it is issued.
 * @param sessionTerms how long a connection lives when the person leaves the durability box
 *   unticked, [durableTerms] when they tick it. Both come from the store that will enforce them, so
 *   the dialog cannot state a term the deployment does not keep.
 * @param disconnectAvailable whether this app holds anything for this person — whether the way out
 *   is worth offering.
 */
internal data class PodConsentScreen(
  val podName: String,
  val podBaseUrl: String,
  val clientId: String,
  val clientName: String,
  val clientUri: String?,
  val logoUri: String?,
  val redirectUri: String,
  val state: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  val csrfToken: String,
  val webId: String,
  val contexts: List<PodConsentContext>,
  val isOwner: Boolean,
  val publicContexts: List<String>,
  val publicReadPreselected: Boolean,
  val durablePreselected: Boolean,
  val sessionTerms: PodRefreshTokenStore.Terms,
  val durableTerms: PodRefreshTokenStore.Terms,
  val disconnectAvailable: Boolean,
)

/**
 * One context in the dialog, with the three permissions it can carry.
 *
 * **The property names are the template's.** `templates/consent.html` reads `ctx.uri`,
 * `ctx.relativePath`, `ctx.readGranted`, `ctx.writeGranted` and `ctx.manageGranted` by reflection,
 * so renaming one here fails at render time and not at compile time.
 */
internal data class PodConsentContext(
  val uri: String,
  val relativePath: String,
  val label: String,
  val readGranted: Boolean,
  val writeGranted: Boolean,
  val manageGranted: Boolean,
)
