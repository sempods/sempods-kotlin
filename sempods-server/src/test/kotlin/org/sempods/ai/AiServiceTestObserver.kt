package org.sempods.ai

import com.google.inject.Binder
import org.sempods.commons.guice.GuiceAppTestProxy
import io.mockk.spyk

class AiServiceTestObserver : GuiceAppTestProxy<AiService>(
  type = AiService::class,
  globalDefaultBehavior = AiServiceTestImpl(),
) {

  fun <D : AiService, R> observeWithDelegate(delegate: D, block: (delegate: D) -> R): R = observe(
    delegate = delegate.also(injector::injectMembers),
    useDelegateResult = true,
    block = block,
  )

  fun <R> observeWithTestImpl(block: (delegate: AiServiceTestImpl) -> R): R = observe(
    delegate = spyk(
      AiServiceTestImpl()
        .also(injector::injectMembers)
    ),
    useDelegateResult = true,
    block = block,
  )

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val observer = AiServiceTestObserver()
      binder.bind(AiService::class.java).toInstance(observer.injectableProxy)
      binder.bind(AiServiceTestObserver::class.java).toInstance(observer)
    }
  }
}
