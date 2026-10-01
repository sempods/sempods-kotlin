package org.sempods.auth

/**
 * A sign-in parked while the browser is at the id-server: the `/authorize` or service consent to
 * resume, and what proves the return belongs to it. [PodLoginStateStore] keeps it.
 *
 * @param codeVerifier never travels through the browser. Presenting it at the id-server's token
 *   endpoint is what proves this is the same party that started the flow.
 * @param nonce ties the *token* to this request, so one minted for an earlier login of the same
 *   person — still signed, still unexpired — cannot stand in for it.
 * @param prompt what the client asked to have re-prompted, minus the force-reauth values that
 *   were satisfied by this very login. Carrying the original would loop the user straight back
 *   into another sign-in.
 * @param redirectUri `null` only on a service consent opened without one.
 */
data class PendingLogin(
  val pod: String,
  val clientId: String,
  val redirectUri: String?,
  val clientState: String?,
  val scope: String?,
  val prompt: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  val codeVerifier: String,
  val nonce: String,
  /**
   * The login-CSRF pin — the value the same browser must present back as a cookie. `state` alone
   * is a bearer, so without this a captured login URL completes in whoever's browser opens it.
   */
  val browserPin: String,
  /** Whether this is a parked service consent for the service [clientId]; else an `/authorize`. */
  val serviceConsent: Boolean = false,
) {
  /**
   * Where a sign-in that failed is reported: [redirectUri] for an `/authorize`, and nowhere for a
   * service consent, whose return address hears only the owner's decision.
   */
  val errorRedirectUri: String? get() = redirectUri.takeUnless { serviceConsent }
}
