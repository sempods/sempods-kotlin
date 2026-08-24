package org.sempods.updates

interface SempodsUpdate {

  val name: String

  /**
   * Whether this update must finish **before** the server accepts requests.
   *
   * `false` (default) is right for an update that only adds data the running system does not read
   * yet — a backfill of a secondary collection, say. It runs on a daemon thread while the server is
   * already serving.
   *
   * `true` is required as soon as an update changes what existing data *means*, because the code
   * around it already assumes the new meaning from the first request on. A migration that renames
   * identifiers is the clear case: until it has run, the registry still holds the old names while
   * routes, scopes and builders speak the new ones, so a request in that window gets a wrong answer
   * (404/403) rather than a slow one — and its writes race the rewrite.
   *
   * The cost of `true` is boot time: [SempodsUpdater.runUpdates] is invoked while the injector is
   * built, so a blocking update delays the point where Jetty starts.
   */
  val blocking: Boolean get() = false

  fun run()
}
