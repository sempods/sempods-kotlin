package org.sempods.mcp.core

import com.google.inject.Provides
import com.google.inject.Singleton
import com.mongodb.client.MongoDatabase
import org.sempods.commons.guice.BaseModule

/**
 * The core's stateful artefact, wired.
 *
 * There is one binding here today, and the module is still worth its lines for the reason
 * `SempodsAuthCoreModule` gives: an artefact added to this module reaches both surfaces, where a
 * `@Provides` per service is a thing each of them has to be told about separately — and a binding
 * nobody remembered to add fails at runtime.
 *
 * Everything else in `sempods-mcp-core` is constructed rather than injected: a catalog, an envelope
 * and an executor that takes its pod client as a parameter. Only the store needs a database.
 *
 * Installing is optional. Guice is `compileOnly` here, so a consumer wiring the store by hand — or
 * into something that is not Guice — inherits no container.
 *
 * A `data class` because it carries a `@Provides` method and is installed by two services: Guice
 * deduplicates modules by `equals`, and two instances it cannot compare would bind the same key
 * twice and refuse to build the injector.
 *
 * @param reauthorizeChallengeCollection each surface keeps its own database, so both name the
 *   collection the same way and the default is what either gets. It stays a parameter because a
 *   test points an instance at a collection of its own, and because a deployment that puts two
 *   surfaces in one database would have to tell them apart.
 */
data class SempodsMcpCoreModule(
  private val reauthorizeChallengeCollection: String = REAUTHORIZE_CHALLENGES,
) : BaseModule() {

  @Provides
  @Singleton
  fun reauthorizeChallengeStore(db: MongoDatabase): ReauthorizeChallengeStore =
    ReauthorizeChallengeStore(db, reauthorizeChallengeCollection)

  companion object {

    /**
     * Named here rather than at each installing service: the two surfaces used to spell it
     * `sempods.reauthChallenges` and `mcp.reauthChallenges`, which was a difference that said
     * nothing — each has a database of its own, so the prefix carried no information the database
     * name did not already carry.
     */
    const val REAUTHORIZE_CHALLENGES = "oauth.reauthChallenges"
  }
}
