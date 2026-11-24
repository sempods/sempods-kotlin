package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.util.concurrent.ConcurrentHashMap

/**
 * The lines [block] wrote to the log, for a test that asserts on a formatted message —
 * `docs/logging.md` §"Three rules" is only checkable there and not in the code.
 *
 * Three traps, all of them the logger being process-wide while these suites run their classes
 * concurrently:
 *
 * - **The appender sees every line that logger wrote, not only this thread's.** Put something
 *   unique in the value under test and select on that; a `single { }` on a marker a sibling also
 *   writes passes alone and fails in a full run.
 * - **The level is raised to TRACE**, because `gradle/logback-test.xml` runs at INFO and a DEBUG
 *   line would otherwise be absent rather than wrong. Concurrent captures of one logger are
 *   counted, so the last one out restores the level the first one found.
 * - **The code under test may hold a logger that never reaches logback.** It took that logger
 *   when it initialized, usually long before the capture started. If SLF4J was still binding at
 *   that moment, the logger can stay a substitute with no delegate, and its lines are lost.
 *   Nothing done at [start] can reach it. [BindSlf4jBeforeTests] binds SLF4J before any test
 *   class runs, so this cannot happen. An empty capture where the code clearly logged means the
 *   listener did not run in that JVM.
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
   * While SLF4J binds, every thread but the binding one gets a `SubstituteLoggerFactory`, so a
   * plain cast would be flaky. [boundLoggerFactory] waits for the binding to finish.
   */
  private fun logbackContext(): LoggerContext {
    val factory = boundLoggerFactory()
    return factory as? LoggerContext
      ?: error("logback is not the SLF4J binding of this test JVM: ${factory::class.qualifiedName}")
  }
}
