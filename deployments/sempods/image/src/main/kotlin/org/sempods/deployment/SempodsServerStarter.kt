package org.sempods.deployment

import com.google.inject.Guice
import org.sempods.commons.config.Env
import org.sempods.commons.logging.LoggingCtx
import org.sempods.commons.logging.LoggingInitializer
import org.sempods.SempodsModule
import org.eclipse.jetty.server.Server
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess

/**
 * The pod server as its own process.
 *
 * Nothing here consumes a pod service in-process, because nothing here is a consumer at all — the
 * applications that use a pod ship as their own artifacts and arrive over HTTP. That was already
 * true while they shared a JVM with this one; this artifact only makes it structural.
 */
object SempodsServerStarter {

  @JvmStatic
  fun main(args: Array<String>) {

    // First statement on purpose: several configuration values are companion `val`s evaluated on
    // class initialization, so anything loaded after the first config read arrives too late.
    Env.loadFileWalkingUp(LOCAL_ENV_PATH)

    LoggingInitializer.initialize()

    val injector = LoggingCtx.withLabels("phase" to "initialize") {
      logger.info { "Starting sempods pod server" }

      logger.info { "Initialize Guice..." }
      val injector = try {
        // Two modules, and the second one has to be here rather than inside the first: the media
        // store a deployment selects may live in a sibling module that `:sempods` cannot name, so
        // the composition is the only place that can choose one. See `SempodsMediaModule`.
        Guice.createInjector(SempodsModule(), SempodsMediaModule)
      } catch (e: Throwable) {
        // Exit rather than let the exception escape `main` — see `BackendStarter` for the failure
        // this prevents: a non-daemon thread keeping the JVM alive after a failed start, so the
        // container stays "Up" with nothing listening and the restart policy never fires.
        logger.error(e) { "Startup failed — exiting so the restart policy can retry" }
        exitProcess(1)
      }
      logger.info { "Initialized Guice" }

      return@withLabels injector
    }

    // TODO: lifecycle management
    val server = injector.getInstance(Server::class.java)
    try {
      logger.info { "Starting Jetty Server..." }
      server.join()
    } finally {
      logger.info { "Stopping Jetty server..." }
      server.destroy()
      logger.info { "Jetty Server stopped" }
    }
  }

  /**
   * The local override file, if this process was started from a working copy.
   *
   * Which file that is, is a deployment decision, so it is named here rather than in `commons`:
   * that module is the shared base and has no business knowing a path under `deployments/`. Another
   * deployment in the same working copy names the same file — locally the processes then run
   * against one MongoDB.
   */
  private const val LOCAL_ENV_PATH = "deployments/local/env/local.env"

  private val logger = KotlinLogging.logger {}
}
