package org.sempods.updates

import com.google.inject.Inject
import com.google.inject.Injector
import java.util.concurrent.Executors
import io.github.oshai.kotlinlogging.KotlinLogging

class SempodsUpdater {

  // Empty since the legacy decommission retired the backup backfill. The
  // mechanism stays: it is what a one-time data update registers with until the versioned migration
  // registry replaces it (the maintainer's internal roadmap).
  private val updates: List<SempodsUpdate> = emptyList()

  private val updateExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "sempods-updates").apply {
      isDaemon = true
    }
  }

  /**
   * Runs the registered updates, split by [SempodsUpdate.blocking].
   *
   * This method is called while Guice builds the injector (the updater is an eager singleton), and
   * `BackendStarter` obtains the Jetty `Server` from that injector afterwards — so anything run
   * synchronously here is finished before the first request is accepted, and anything submitted to
   * the executor runs alongside a serving instance.
   *
   * That distinction is the whole point: an update that changes what existing data *means* has to
   * be complete before traffic arrives, because the surrounding code already assumes the new
   * meaning. Serving during such a migration does not produce slow answers, it produces wrong ones.
   */
  @Inject
  fun runUpdates(injector: Injector) {
    val (blocking, background) = updates.partition { it.blocking }

    if (blocking.isNotEmpty()) {
      logger.info { "[sempods/updates] Running ${blocking.size} blocking update(s) BEFORE startup completes" }
      blocking.forEach { runUpdate(injector, it) }
      logger.info { "[sempods/updates] Blocking updates finished — server may start" }
    }

    if (background.isEmpty()) {
      return
    }
    logger.info { "[sempods/updates] Scheduling ${background.size} background update(s)" }
    updateExecutor.submit {
      background.forEach { runUpdate(injector, it) }
      logger.info { "[sempods/updates] Background updates finished" }
    }
  }

  /**
   * One update, with its failure isolated: a broken update must not take the others with it, and a
   * blocking one must not prevent the server from starting at all. What it *does* cost is
   * correctness for whatever it failed to migrate — which is why the log is SEVERE and an update
   * that changes what stored data means should report its own end state rather than only its work.
   */
  private fun runUpdate(injector: Injector, update: SempodsUpdate) {
    try {
      logger.info { "[sempods/updates] Starting update '${update.name}'" }
      injector.injectMembers(update)
      update.run()
      logger.info { "[sempods/updates] Finished update '${update.name}'" }
    } catch (e: Exception) {
      logger.error(e) { "[sempods/updates] Update failed '${update.name}'" }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
