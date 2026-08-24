package org.sempods.auth.core

import com.google.inject.Provides
import com.google.inject.Singleton
import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule

/**
 * The core's artefacts, wired.
 *
 * A consumer installs this instead of repeating a provider per class — the same trade
 * `MongoModule` makes for the driver. What it is worth is not the four lines it saves today but
 * what happens when the core grows: an artefact added here reaches every service that installs
 * the module, rather than being a thing each of the three has to be told about separately. A
 * binding nobody remembered to add is the failure mode this prevents, and it is one that only
 * shows up at runtime.
 *
 * **The parameters are parameters, not environment lookups.** They arrive from the service's own
 * typed configuration, which is where they are already read, and which is what keeps the local
 * mains working — they construct their config by hand and never touch the environment. `Env`
 * carries a standing note that a global lookup is a hidden dependency; a module that read the
 * environment itself would walk exactly that back.
 *
 * Installing is optional. Guice is `compileOnly` here, so a consumer that wires these classes by
 * hand — or wires them into something that is not Guice — inherits no container.
 *
 * A `data class` because it carries `@Provides` methods and is installed by three services: Guice
 * deduplicates modules by `equals`, and two instances it cannot compare would bind the same keys
 * twice and refuse to build the injector.
 *
 * @param authorizationCodeCollection each service keeps its own — codes minted by one are
 *   meaningless to another — but each also has its own database, so all three name the collection
 *   the same way and the default is what any of them gets. It stays a parameter because a test
 *   points an instance at a collection of its own.
 * @param allowLoopbackRedirects development only. A loopback redirect address in production means
 *   an authorization code can be intercepted by anything running on the user's machine, so this is
 *   passed in from the service's config rather than defaulted to anything but `false`.
 */
data class SempodsAuthCoreModule(
  private val authorizationCodeCollection: String = AUTHORIZATION_CODES,
  private val allowLoopbackRedirects: Boolean = false,
) : BaseModule() {

  @Provides
  @Singleton
  fun authorizationCodeStore(db: MongoDatabase): AuthorizationCodeStore =
    AuthorizationCodeStore(db, authorizationCodeCollection)

  /**
   * Who may be handed a response, and where.
   *
   * Bound as the interface: the pod server accepts `dyn:` registrations alongside `did:web:` and
   * will supply its own implementation, so this default is what a service gets when it has not
   * said otherwise — not a decision made for it.
   */
  @Provides
  @Singleton
  fun clientRedirectPolicy(): ClientRedirectPolicy = DidWebRedirectPolicy(allowLoopbackRedirects)

  companion object {

    /**
     * Named here rather than at each installing service: the three used to spell it
     * `sempods.authCodes`, `mcp.authCodes` and `auth_codes` — three spellings of one thing, and
     * none of the three carried information the database name did not already carry.
     */
    const val AUTHORIZATION_CODES = "oauth.authCodes"
  }
}
