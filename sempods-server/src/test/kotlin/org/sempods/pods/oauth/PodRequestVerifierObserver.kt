package org.sempods.pods.oauth

import com.google.inject.Binder
import com.google.inject.Provider
import org.sempods.commons.guice.GuiceAppTestProxy
import org.sempods.pods.oauth.spi.PodRequestVerifier

/**
 * The [PodRequestVerifier] the suite binds: the real [PodTokenAuthenticator], with a
 * [PodRequestVerifierTestImpl] in front of it for the trace that asks for one.
 *
 * The authenticator comes through a [Provider] because it is built by the same injector this
 * observer is bound in.
 */
class PodRequestVerifierObserver private constructor(
  real: PodRequestVerifier,
) : GuiceAppTestProxy<PodRequestVerifier>(
  type = PodRequestVerifier::class,
  globalDefaultBehavior = real,
) {

  /** Runs [block] with a fresh [PodRequestVerifierTestImpl] answering this trace's requests. */
  fun <R> observeWithTestImpl(block: (delegate: PodRequestVerifierTestImpl) -> R): R = observe(
    delegate = PodRequestVerifierTestImpl(real = globalDefaultBehavior),
    useDelegateResult = true,
    block = block,
  )

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val authenticator: Provider<PodTokenAuthenticator> = binder.getProvider(PodTokenAuthenticator::class.java)
      val observer = PodRequestVerifierObserver(
        real = PodRequestVerifier { request, pod -> authenticator.get().verify(request, pod) },
      )
      binder.bind(PodRequestVerifier::class.java).toInstance(observer.injectableProxy)
      binder.bind(PodRequestVerifierObserver::class.java).toInstance(observer)
    }
  }
}
