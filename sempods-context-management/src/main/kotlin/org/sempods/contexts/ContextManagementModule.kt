package org.sempods.contexts

import com.google.inject.multibindings.OptionalBinder
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.jaxrs.JaxRsApplicationModule
import org.sempods.pods.contexts.ContextManagementInstalled

/**
 * Installs context creation and deletion beside `SempodsModule`: the routes, and the marker that
 * tells the server it serves them, so it advertises `contexts:manage` and reports
 * `manageableContext` (`ContextManagementSupport`). A composition without this module serves the
 * registry's reads alone.
 *
 * A `data class` for the reason `PodMediaModule` is one: more than one composition installs it, and
 * Guice deduplicates modules by `equals`.
 */
data class ContextManagementModule(
  private val httpPort: Int,
) : BaseModule() {

  override fun configure() {
    OptionalBinder.newOptionalBinder(binder(), ContextManagementInstalled::class.java)
      .setBinding()
      .toInstance(Installed)
    JaxRsApplicationModule.bindEndpoints(binder(), httpPort, PodContextLifecycleEndpoint::class.java)
  }

  private object Installed : ContextManagementInstalled
}
