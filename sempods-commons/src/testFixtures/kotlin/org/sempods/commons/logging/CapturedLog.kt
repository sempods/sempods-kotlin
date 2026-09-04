package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The lines [block] wrote to the log, for a test that asserts on a formatted message —
 * `docs/logging.md` §"Three rules" is only checkable there and not in the code.
 *
 * Two traps, both of them the logger being process-wide while these suites run their classes
 * concurrently:
 *
 * - **The appender sees every line that logger wrote, not only this thread's.** Put something
 *   unique in the value under test and select on that; a `single { }` on a marker a sibling also
 *   writes passes alone and fails in a full run.
 * - **The level is raised to TRACE**, because `gradle/logback-test.xml` runs at INFO and a DEBUG
 *   line would otherwise be absent rather than wrong. Concurrent captures of one logger are
 *   counted, so the last one out restores the level the first one found.
 *
 * `inline`, so the block may suspend — several cases drive a Ktor test application.
 */
object CapturedLog {

  /** The messages [block] logged through the logger [type] would use. */
  inline fun linesFrom(type: Class<*>, block: () -> Unit): List<String> = linesFrom(type.name, block)

  /** The messages [block] logged through the logger named [name] — for a logger that names itself. */
  inline fun linesFrom(name: String, block: () -> Unit): List<String> {
    val capture = start(name)
    try {
      block()
    } finally {
      capture.stop()
    }
    return capture.lines()
  }

  @PublishedApi
  internal fun start(name: String): Capture {
    val logger = logbackContext().getLogger(name)
    val appender = ListAppender<ILoggingEvent>()
    raise(logger)
    appender.start()
    logger.addAppender(appender)
    return Capture(logger, appender)
  }

  @PublishedApi
  internal class Capture(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
  ) {

    fun stop() {
      logger.detachAppender(appender)
      appender.stop()
      lower(logger)
    }

    /**
     * Read under the appender's own monitor: `AppenderBase.doAppend` is `synchronized`, so appends
     * cannot corrupt each other — but the list behind them is a plain `ArrayList`, and a request
     * thread still inside `doAppend` when [stop] detaches would otherwise be writing to it while
     * this iterates.
     */
    fun lines(): List<String> = synchronized(appender) { appender.list.map { it.formattedMessage } }
  }

  /** How many captures are currently holding a logger at TRACE, and the level to put back. */
  private class Raised(val original: Level?, val holders: Int)

  private val raised = ConcurrentHashMap<String, Raised>()

  private fun raise(logger: Logger) {
    raised.compute(logger.name) { _, current ->
      if (current == null) {
        val original = logger.level
        logger.level = Level.TRACE
        Raised(original, holders = 1)
      } else {
        Raised(current.original, current.holders + 1)
      }
    }
  }

  private fun lower(logger: Logger) {
    raised.compute(logger.name) { _, current ->
      // `null` only if a caller stopped a capture twice; leaving the level alone is the safe read.
      if (current == null) return@compute null
      if (current.holders > 1) {
        Raised(current.original, current.holders - 1)
      } else {
        logger.level = current.original
        null
      }
    }
  }

  /**
   * SLF4J hands a `SubstituteLoggerFactory` to every thread but the one currently binding the
   * provider, and these suites run their classes concurrently — so a plain cast is flaky. The
   * window closes in microseconds.
   */
  private fun logbackContext(): LoggerContext {
    repeat(500) {
      val factory = LoggerFactory.getILoggerFactory()
      if (factory is LoggerContext) return factory
      Thread.sleep(10)
    }
    error("logback never became the SLF4J binding of this test JVM")
  }
}
