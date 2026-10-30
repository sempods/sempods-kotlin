package org.sempods

import com.google.inject.Binder
import org.sempods.auth.core.FakeOidcProvider
import org.sempods.auth.core.HttpTransport
import org.sempods.commons.guice.GuiceAppTestProxy

/**
 * The [HttpTransport] the suite binds: the id-server as a [FakeOidcProvider], with a
 * [FakeOidcProvider.variant] in front of it for the trace that asks for one.
 *
 * The variant shares the suite's keys and armed sign-ins, so a sign-in armed on either completes
 * on both. What a test changes on it reaches only that test's requests, and only the token
 * exchange: the server discovers the id-server once, for every pod (`OidcRelyingPartyCache`).
 */
class IdServerObserver private constructor(
  /** The suite's id-server, which every request reaches outside a `withSetup` block. */
  val idServer: FakeOidcProvider,
) : GuiceAppTestProxy<HttpTransport>(
  type = HttpTransport::class,
  globalDefaultBehavior = idServer,
) {

  /** Runs [block] with a fresh [FakeOidcProvider.variant] answering this trace's requests. */
  fun <R> observeWithTestImpl(block: (delegate: FakeOidcProvider) -> R): R = observe(
    delegate = idServer.variant(),
    useDelegateResult = true,
    block = block,
  )

  companion object {
    /**
     * The identity issuer these tests run against. `SempodsTestModule` binds `idBaseUrl` to it,
     * and the relying party refuses a provider advertising anything else.
     */
    const val ISSUER = "http://test-sempods-auth"

    @JvmStatic
    fun bindTestProxy(binder: Binder) {
      val observer = IdServerObserver(FakeOidcProvider(ISSUER))
      binder.bind(HttpTransport::class.java).toInstance(observer.injectableProxy)
      binder.bind(FakeOidcProvider::class.java).toInstance(observer.idServer)
      binder.bind(IdServerObserver::class.java).toInstance(observer)
    }
  }
}
