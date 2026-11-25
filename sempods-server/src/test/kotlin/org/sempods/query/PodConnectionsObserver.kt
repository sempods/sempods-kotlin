package org.sempods.query

import com.google.inject.Binder
import com.google.inject.Provider
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.sempods.commons.guice.GuiceAppTestProxy
import org.sempods.query.impls.mongo.MongoPodConnections
import org.sempods.query.spi.PodConnections
import org.sempods.spec.PodRef

/**
 * The [PodConnections] the suite binds: the real [MongoPodConnections], with a
 * [PodConnectionsTestImpl] in front of it for the trace that asks for one.
 *
 * `FindService` re-binds the caller's trace on each adapter's thread, so the SPARQL find adapter
 * reaches the block's test implementation as the expander does.
 */
class PodConnectionsObserver private constructor(
  real: PodConnections,
) : GuiceAppTestProxy<PodConnections>(
  type = PodConnections::class,
  globalDefaultBehavior = real,
) {

  /** Runs [block] with a fresh [PodConnectionsTestImpl] answering this trace's reads. */
  fun <R> observeWithTestImpl(block: (delegate: PodConnectionsTestImpl) -> R): R = observe(
    delegate = PodConnectionsTestImpl(real = globalDefaultBehavior),
    useDelegateResult = true,
    block = block,
  )

  companion object {

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val connections: Provider<MongoPodConnections> = binder.getProvider(MongoPodConnections::class.java)
      val observer = PodConnectionsObserver(
        real = object : PodConnections {
          override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T =
            connections.get().withConnection(pod, block)
        },
      )
      binder.bind(PodConnections::class.java).toInstance(observer.injectableProxy)
      binder.bind(PodConnectionsObserver::class.java).toInstance(observer)
    }
  }
}
