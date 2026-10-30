package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.auth.PendingLogin
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.BrowserSignIn
import org.sempods.pods.HostedPod

/**
 * Parks a browser request behind a sign-in at the id-server: `/authorize` and the service consent.
 *
 * The round trip is [BrowserSignIn]'s; the pod's is which relying party — one per pod — and that
 * the browser pin goes into a cookie scoped to that pod's callback. [PodSignInCompletion]
 * completes it at the callback.
 */
class PodSignIn @Inject internal constructor(
  private val identityProvider: PodIdentityProvider,
  private val loginStateStore: PodLoginStateStore,
) {

  /**
   * Parks [pending] and answers where the browser goes, or `null` where the id-server could not be
   * discovered.
   *
   * @param prompt forwarded to the id-server (`login`, `select_account`), or `null`.
   */
  internal fun park(pod: HostedPod, prompt: String?, pending: PendingLogin): BrowserSignIn.Started? =
    identityProvider.relyingPartyOrNull(pod.name)?.let { loginStateStore.signIn.begin(it, pending, prompt) }
}
