package org.sempods.commons.jaxrs

import com.google.inject.Binder
import com.google.inject.Singleton
import com.google.inject.multibindings.Multibinder
import org.sempods.commons.guice.BaseModule

/**
 * The JAX-RS bootstrap for one connector, plus the set binder every endpoint registration
 * contributes to.
 *
 * A `data class` because more than one composition installs it — a deployment that runs two
 * applications in one injector installs it from each. Guice deduplicates modules by `equals`; two
 * instances it cannot compare would be configured twice.
 */
data class JaxRsApplicationModule(val httpPort: Int) : BaseModule() {

  override fun configure() {
    install(JaxRsServerModule)

    Multibinder.newSetBinder(binder(), JaxRsEndpointBinding::class.java)
    // Initialised empty so `ApiExceptionMapper` can always be injected with it — most compositions
    // declare nothing, and a set binder with no contribution is still a binding.
    Multibinder.newSetBinder(binder(), SecretPathSegment::class.java)
  }

  companion object {

    fun bindEndpoints(binder: Binder, httpPort: Int, vararg endpoints: Class<out Any>) {

      val endpointsSetBinder = Multibinder.newSetBinder(binder, JaxRsEndpointBinding::class.java)
      for (jaxRsResourceType in endpoints) {
        binder.bind(jaxRsResourceType).`in`(Singleton::class.java)
      }

      endpointsSetBinder.addBinding().toInstance(
        JaxRsEndpointBinding(
          httpPort = httpPort,
          endpoints = endpoints.toList(),
        )
      )
    }

    /**
     * Declares that whatever follows [afterSegment] in a request path is a credential — see
     * [SecretPathSegment] for why a route with one in its URL has to say so.
     */
    fun declareSecretPathSegment(binder: Binder, afterSegment: String) {
      Multibinder.newSetBinder(binder, SecretPathSegment::class.java)
        .addBinding()
        .toInstance(SecretPathSegment(afterSegment))
    }
  }
}
