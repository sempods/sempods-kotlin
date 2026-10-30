package org.sempods.auth

/**
 * A sign-in parked while the browser is at the id-server: the `/authorize` or service consent to
 * resume. [PodLoginStateStore] keeps it; what proves the return belongs to it — `state`, browser
 * pin, verifier, nonce, `iss` — is `BrowserSignIn`'s, and so is which pod it returns to: a sign-in
 * completes only at the callback address it was begun for.
 *
 * @param prompt what the client asked to have re-prompted, minus the force-reauth values that
 *   were satisfied by this very login. Carrying the original would loop the user straight back
 *   into another sign-in.
 * @param redirectUri `null` only on a service consent opened without one.
 */
data class PendingLogin(
  val clientId: String,
  val redirectUri: String?,
  val clientState: String?,
  val scope: String?,
  val prompt: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  /** Whether this is a parked service consent for the service [clientId]; else an `/authorize`. */
  val serviceConsent: Boolean = false,
) {
  /**
   * Where a sign-in that failed is reported: [redirectUri] for an `/authorize`, and nowhere for a
   * service consent, whose return address hears only the owner's decision.
   */
  val errorRedirectUri: String? get() = redirectUri.takeUnless { serviceConsent }
}
