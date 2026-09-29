package org.sempods.updates

import com.google.inject.Inject
import com.google.inject.Injector
import java.util.concurrent.Executors
import io.github.oshai.kotlinlogging.KotlinLogging

/** Runs a fixed list of startup maintenance tasks on every boot, without recording run history. */
class SempodsUpdater {

  private val updates: List<SempodsUpdate> = listOf(
    DcrFingerprintUniqueness(),
  )

  private val updateExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "sempods-updates").apply {
      isDaemon = true
    }
  }

  /**
   * Invoked while Guice builds the injector. Runs blocking tasks before scheduling background
   * tasks, as defined by [SempodsUpdate.blocking]. Task exceptions are logged; remaining tasks
   * and server startup continue.
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
