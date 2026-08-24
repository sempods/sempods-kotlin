package org.sempods.pods.media

import org.sempods.api.pod.system.media.PodMediaEndpoint
import org.sempods.api.system.admin.media.AdminMediaEndpoint
import org.sempods.commons.guice.BaseModule
import org.sempods.commons.jaxrs.JaxRsApplicationModule

/**
 * Everything a pod server needs once a deployment has decided that it holds binaries: the store, the
 * limits, the SSRF guard, the facade over them, and the two routes that reach them.
 *
 * **What it does not decide is which store.** That choice stays with the composition, because
 * `S3PodMediaStore` ships in `:sempods-media-s3` — a sibling that depends on this module, so nothing
 * here can name it. The composition builds the three instances and installs this; see
 * `SempodsMediaModule` in `:deployments:sempods:image` for the production selection and
 * `docs/media.md` §"The seam".
 *
 * A module rather than three copies of the same five `bind` calls: the deployment and every test
 * composition that runs media each used to restate them, so a binding added here reached one
 * composition and silently not the others.
 *
 * `PodMediaDao` is deliberately **not** here — `SempodsModule` binds the registry unconditionally,
 * which is what lets a deployment with no store keep its lifecycle cascades. Only the parts that
 * need bytes live in this module.
 *
 * A `data class` because more than one composition installs it: Guice deduplicates modules by
 * `equals`, and two instances it cannot compare would bind the same keys twice and refuse to build
 * the injector.
 */
data class PodMediaModule(
  private val store: PodMediaStore,
  private val config: PodMediaConfig,
  private val addressGuard: MediaSourceAddressGuard,
  private val httpPort: Int,
) : BaseModule() {

  override fun configure() {
    bind<PodMediaStore>().toInstance(store)
    bind<PodMediaConfig>().toInstance(config)
    bind<PodMediaFacade>().asSingleton()

    // Bound explicitly rather than left to a Kotlin default argument, which Guice does not see.
    // Which policy a composition passes is a real decision: production refuses everything that is
    // not a global unicast address, and the one guard that accepts loopback lives in a test source
    // set — see `LoopbackOnlyAddressGuard`.
    bind<MediaSourceAddressGuard>().toInstance(addressGuard)
    bind<MediaSourceFetcher>().asSingleton()

    // The routes come with the store, so a deployment that configures none does not answer
    // `_system/media` at all rather than answering an error per call. A second module contributing
    // endpoints is how `JaxRsApplicationModule.bindEndpoints` is built — a Multibinder over
    // `JaxRsEndpointBinding`, not a single binding this would be fighting with.
    //
    // The maintenance routes come along for the same reason: with no store nothing was ever
    // written, so there is nothing to sweep and nothing to reconcile against.
    JaxRsApplicationModule.bindEndpoints(
      binder(),
      httpPort,
      PodMediaEndpoint::class.java,
      AdminMediaEndpoint::class.java,
    )
  }
}
