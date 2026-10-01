package org.sempods.pods.oauth

import com.google.inject.Inject
import com.google.inject.Singleton
import org.sempods.spec.PodRef
import java.util.concurrent.ConcurrentHashMap

/**
 * The [PodRequestVerifier] the suite's injector binds: the real [PodTokenAuthenticator], unless a
 * test has installed another verifier *for its own pod*.
 *
 * Keyed by pod name rather than held globally, because the injector is shared and classes run
 * concurrently — a test that swaps the verifier for everyone would break its neighbours. A pod name
 * is unique per test, so nothing else sees the swap.
 */
@Singleton
class PodRequestVerifierTestDouble @Inject constructor(
  private val real: PodTokenAuthenticator,
) : PodRequestVerifier {

  private val replacements = ConcurrentHashMap<String, PodRequestVerifier>()

  /** Answers for [podName] with [verifier] from now on. */
  fun replaceFor(podName: String, verifier: PodRequestVerifier) {
    replacements[podName] = verifier
  }

  override fun verify(request: PodResourceRequest, pod: PodRef): PodTokenAuthentication =
    (replacements[pod.name] ?: real).verify(request, pod)
}
