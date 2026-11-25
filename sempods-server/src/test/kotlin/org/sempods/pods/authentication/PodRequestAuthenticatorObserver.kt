package org.sempods.pods.authentication

import com.google.inject.Binder
import com.google.inject.Provider
import org.sempods.commons.guice.GuiceAppTestProxy
import org.sempods.pods.authentication.impls.oauth.BearerPodRequestAuthenticator
import org.sempods.pods.authentication.spi.PodRequestAuthenticator

/**
 * The [PodRequestAuthenticator] the suite binds: the real [BearerPodRequestAuthenticator], with a
 * [PodRequestAuthenticatorTestImpl] in front of it for the trace that asks for one.
 *
 * The authenticator comes through a [Provider] because it is built by the same injector this
 * observer is bound in.
 */
class PodRequestAuthenticatorObserver private constructor(
  real: PodRequestAuthenticator,
) : GuiceAppTestProxy<PodRequestAuthenticator>(
  type = PodRequestAuthenticator::class,
  globalDefaultBehavior = real,
) {

  /** Runs [block] with a fresh [PodRequestAuthenticatorTestImpl] answering this trace's requests. */
  fun <R> observeWithTestImpl(block: (delegate: PodRequestAuthenticatorTestImpl) -> R): R = observe(
    delegate = PodRequestAuthenticatorTestImpl(real = globalDefaultBehavior),
    useDelegateResult = true,
    block = block,
  )

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val authenticator: Provider<BearerPodRequestAuthenticator> =
        binder.getProvider(BearerPodRequestAuthenticator::class.java)
      val observer = PodRequestAuthenticatorObserver(
        real = PodRequestAuthenticator { pod, request -> authenticator.get().authenticate(pod, request) },
      )
      binder.bind(PodRequestAuthenticator::class.java).toInstance(observer.injectableProxy)
      binder.bind(PodRequestAuthenticatorObserver::class.java).toInstance(observer)
    }
  }
}
