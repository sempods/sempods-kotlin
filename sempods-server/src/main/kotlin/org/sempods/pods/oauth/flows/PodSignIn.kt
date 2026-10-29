package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.PendingLogin
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.pods.HostedPod

/**
 * Parks a browser request behind a sign-in at the id-server: `/authorize` and the service consent.
 *
 * The round trip is `BrowserSignIn`'s; what is the pod's is which relying party — one per pod —
 * and that the browser pin goes into a cookie scoped to that pod's callback. [PodSignInCompletion]
 * completes it at the callback.
 */
class PodSignIn @Inject internal constructor(
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
) {

  /** Where the browser goes, and the pin the adapter puts in a cookie beside it. */
  internal data class Parked(val authorizationUrl: String, val state: String, val browserPin: String)

  /**
   * Parks [pending] and answers where the browser goes, or `null` where the id-server could not be
   * discovered.
   *
   * @param prompt forwarded to the id-server (`login`, `select_account`), or `null`.
   */
  internal fun park(pod: HostedPod, prompt: String?, pending: PendingLogin): Parked? {
    val relyingParty = try {
      identityProvider.relyingParty(pod.name)
    } catch (e: Exception) {
      logger.warn(e) { "[oauth/sign-in] identity provider discovery failed: pod='${pod.name}'" }
      return null
    }
    val started = loginStateStore.signIn.begin(relyingParty, pending, prompt)
    return Parked(started.authorizationUrl, started.state, started.browserPin)
  }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
