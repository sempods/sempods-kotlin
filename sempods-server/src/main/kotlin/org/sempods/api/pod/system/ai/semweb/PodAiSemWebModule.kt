package org.sempods.api.pod.system.ai.semweb

import org.sempods.ai.sem.AiSemFacade
import org.sempods.ai.sem.AiSemShaclGuidanceDeriver
import org.sempods.ai.sem.prompts.SempodsPromptBuilderFactory
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.jaxrs.JaxRsApplicationModule

/**
 * The AI routes and the layer behind them, over whichever `AiService` the installing module binds.
 *
 * `SempodsModule.bindAiService` installs it once a provider is configured; the test suite installs it
 * with a provider that refuses every call. A data class, so the two installs of one port deduplicate.
 */
data class PodAiSemWebModule(private val httpPort: Int) : BaseModule() {

  override fun configure() {
    bind<AiSemShaclGuidanceDeriver>().asSingleton()
    bind<SempodsPromptBuilderFactory>().asSingleton()
    bind<AiSemFacade>().asSingleton()
    JaxRsApplicationModule.bindEndpoints(binder(), httpPort = httpPort, PodAiSemWebEndpoint::class.java)
  }
}
