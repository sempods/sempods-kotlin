package org.sempods.commons.datetime

import com.google.inject.Binder
import org.sempods.commons.guice.GuiceAppTestProxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

class InstantSourceTestObserver: GuiceAppTestProxy<InstantSource>(
  type = InstantSource::class,
  globalDefaultBehavior = InstantSource.system(),
) {

  fun <R> observeRelative(startInstant: String, block: (delegate: InstantSource) -> R): R {
    return observeRelative(
      startInstant = Instant.parse(startInstant),
      block = block,
    )
  }

  fun <R> observeRelative(startInstant: Instant, block: (delegate: InstantSource) -> R): R {
    val offsetDuration = Duration.between(Instant.now(), startInstant)
    return observe(
      delegate = Clock.offset(Clock.systemUTC(), offsetDuration),
      useDelegateResult = true,
      block = block,
    )
  }

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val observer = InstantSourceTestObserver()
      binder.bind(InstantSource::class.java).toInstance(observer.injectableProxy)
      binder.bind(InstantSourceTestObserver::class.java).toInstance(observer)
    }
  }
}
