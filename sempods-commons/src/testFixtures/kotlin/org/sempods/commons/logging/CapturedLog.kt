package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

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
    // `gradle/logback-test.xml` runs every test JVM at INFO, so a DEBUG line would otherwise be
    // absent rather than wrong — and a test asserting on lines it never receives passes.
    val capture = Capture(logger, appender, logger.level)
    logger.level = Level.TRACE
    appender.start()
    logger.addAppender(appender)
    return capture
  }

  @PublishedApi
  internal class Capture(
    private val logger: Logger,
    private val appender: ListAppender<ILoggingEvent>,
    private val restore: Level?,
  ) {

    fun stop() {
      logger.detachAppender(appender)
      appender.stop()
      logger.level = restore
    }

    fun lines(): List<String> = appender.list.map { it.formattedMessage }
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
