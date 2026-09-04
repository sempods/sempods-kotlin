package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * What a block of code wrote to the log, as the lines an incident would be read from.
 *
 * The rule this serves is `docs/logging.md` §"Three rules": a value someone else wrote may appear
 * in a log line, but it may not *end* one. That is only checkable against the formatted message, so
 * a test for it has to read the log rather than the code — and reading the log means an appender.
 *
 * `inline`, so the block may suspend: several of these cases drive a Ktor test application, where
 * the call that produces the line is a suspending one.
 *
 * **The appender sees every line that logger wrote while the block ran, not only this thread's.**
 * These suites run their classes concurrently, so a case that picks its line with `single { }` on a
 * marker its siblings also write passes alone and fails in a full run. Put something unique in the
 * value under test and select on that.
 *
 * The level is raised for the duration, because `gradle/logback-test.xml` runs every test JVM at
 * INFO and a DEBUG line would otherwise be absent rather than wrong — a case asserting on lines it
 * never receives passes. A logger is process-wide, so two concurrent captures of the same one share
 * that level: they are counted, and the first in and last out are what save and restore it.
 * Without the count the second block restores what the first had already replaced, and the level
 * is left at TRACE for the rest of the run.
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

    fun lines(): List<String> = appender.list.map { it.formattedMessage }
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
