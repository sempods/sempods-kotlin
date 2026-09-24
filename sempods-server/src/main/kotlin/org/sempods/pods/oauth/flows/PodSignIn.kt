package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.Secrets
import org.sempods.pods.HostedPod

/**
 * Parks a browser request behind a sign-in at the id-server: `/authorize` and the grant consent.
 *
 * The request stays here under a `state` this server minted, and the identity comes back over a
 * back channel with a verifier that never left this process. `state` is a bearer, so a browser pin
 * is minted beside it and the callback must see it again as a cookie; without it a captured login
 * URL completes in someone else's browser.
 */
class PodSignIn @Inject internal constructor(
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
) {

  /** Where the browser goes, and the pin the adapter puts in a cookie beside it. */
  internal data class Parked(val authorizationUrl: String, val state: String, val browserPin: String)

  /**
   * Parks the request [parked] builds and answers where the browser goes, or `null` where the
   * id-server could not be discovered.
   *
   * @param prompt forwarded to the id-server (`login`, `select_account`), or `null`.
   * @param parked the request to park, given the verifier, nonce and pin minted for it.
   */
  internal fun park(
    pod: HostedPod,
    prompt: String?,
    parked: (codeVerifier: String, nonce: String, browserPin: String) -> PodLoginStateStore.Pending,
  ): Parked? {
    val relyingParty = try {
      identityProvider.relyingParty(pod.name)
    } catch (e: Exception) {
      logger.warn(e) { "[oauth/sign-in] identity provider discovery failed: pod='${pod.name}'" }
      return null
    }
    val started = relyingParty.beginAuthorization(prompt = prompt, state = loginStateStore.newState())
    val browserPin = Secrets.newSecret()
    loginStateStore.create(started.state, parked(started.codeVerifier, started.nonce, browserPin))
    return Parked(started.authorizationUrl, started.state, browserPin)
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
