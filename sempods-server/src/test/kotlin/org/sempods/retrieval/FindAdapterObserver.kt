package org.sempods.retrieval

import com.google.inject.Binder
import com.google.inject.multibindings.Multibinder
import org.sempods.commons.guice.GuiceAppTestProxy

/**
 * The [FindAdapter] the suite binds beside the built-in SPARQL adapter: a [FindAdapterTestImpl]
 * that answers nothing, with a fresh one in front of it for the trace that asks for one.
 *
 * `FindService` re-binds the caller's trace on each adapter's thread, which is what lets a request
 * from a `withSetup` block reach that block's test implementation.
 */
class FindAdapterObserver private constructor() : GuiceAppTestProxy<FindAdapter>(
  type = FindAdapter::class,
  globalDefaultBehavior = FindAdapterTestImpl(),
) {

  /** Runs [block] with a fresh [FindAdapterTestImpl] answering this trace's searches. */
  fun <R> observeWithTestImpl(block: (delegate: FindAdapterTestImpl) -> R): R = observe(
    delegate = FindAdapterTestImpl(),
    useDelegateResult = true,
    block = block,
  )

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val observer = FindAdapterObserver()
      Multibinder.newSetBinder(binder, FindAdapter::class.java).addBinding().toInstance(observer.injectableProxy)
      binder.bind(FindAdapterObserver::class.java).toInstance(observer)
    }
  }
}
