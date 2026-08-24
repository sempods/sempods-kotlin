package org.sempods.commons.logging

import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * The logging entry point: the first statement of every `main` in this repository.
 *
 * It routes `java.util.logging` into slf4j — still needed even though no project code logs through
 * JUL any more, because Jersey, Jetty, the Google API client and Morphia all do, and without the
 * bridge their records bypass logback entirely.
 *
 * Everything else about logging here — the facade / binding split, `logback-base.xml`, and the
 * `LOG_LEVEL` / `LOG_FORMAT` knobs — is in `docs/logging.md`. Note what this method deliberately
 * does *not* do: it does not configure logback. That is `logback.xml`, shipped by whichever
 * artifact owns the `main`, so that a library depending on `commons` inherits no configuration.
 */
object LoggingInitializer {

  fun initialize() {
    installJulBridge()
  }

  private fun installJulBridge() {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
  }
}
