package org.sempods.contexts

import com.google.inject.multibindings.OptionalBinder
import org.sempods.SempodsModule
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.jaxrs.JaxRsApplicationModule
import org.sempods.pods.contexts.ContextManagementInstalled

/**
 * Installs context creation and deletion beside `SempodsModule`: the routes, and the marker that
 * tells the server it serves them, so it advertises `contexts:manage` and reports
 * `manageableContext` (`ContextManagementSupport`). A composition without this module serves the
 * registry's reads alone.
 *
 * The routes go on the connector `SempodsModule` serves the pods on (`SempodsModule.config`), so the
 * marker and the routes cannot name different ones. An `object`, so Guice deduplicates it where more
 * than one composition installs it.
 */
object ContextManagementModule : BaseModule() {

  override fun configure() {
    OptionalBinder.newOptionalBinder(binder(), ContextManagementInstalled::class.java)
      .setBinding()
      .toInstance(Installed)
    JaxRsApplicationModule.bindEndpoints(binder(), SempodsModule.config.httpPort, PodContextLifecycleEndpoint::class.java)
  }

  private object Installed : ContextManagementInstalled
}
