package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import org.slf4j.Logger.ROOT_LOGGER_NAME
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `logback-base.xml` is the one configuration every process in this repository includes, and its
 * two knobs are what an operator is told to reach for in `docs/logging.md` and in the
 * `*.default.env` files. Both are resolved by Joran at configuration time, so nothing else in the
 * build would notice a typo in either — hence this.
 *
 * Configures a throwaway [LoggerContext] rather than the running one, exactly as an application's
 * own `logback.xml` does: through the `<include>`, which is therefore under test too.
 */
class LogbackBaseConfigTest {

  private val includingConfiguration = """
    <configuration>
      <include resource="org/sempods/commons/logging/logback-base.xml"/>
    </configuration>
  """.trimIndent()

  private fun configure(vararg properties: Pair<String, String>): LoggerContext {
    val context = LoggerContext()
    // A throwaway context has no MDC adapter, and `%X` asks every event for one.
    context.mdcAdapter = LogbackMDCAdapter()
    properties.forEach { (key, value) -> context.putProperty(key, value) }
    JoranConfigurator()
      .apply { this.context = context }
      .doConfigure(ByteArrayInputStream(includingConfiguration.toByteArray()))
    return context
  }

  private fun LoggerContext.rootAppenderNames(): List<String> =
    getLogger(ROOT_LOGGER_NAME).iteratorForAppenders().asSequence().map { it.name }.toList()

  @Test
  fun `defaults to INFO on the console`() {
    val context = configure()
    assertEquals(Level.INFO, context.getLogger(ROOT_LOGGER_NAME).level)
    assertEquals(listOf("console"), context.rootAppenderNames())
  }

  @Test
  fun `a caller cannot forge a second console line`() {
    // The console pattern is the one place this can be settled for every call site at once: a
    // value somebody else wrote reaches a log line through dozens of them, and a rule that each
    // has to remember is a rule that one of them will not. `docs/logging.md` §"Three rules".
    val console = configure("LOG_FORMAT" to "console").getLogger(ROOT_LOGGER_NAME).getAppender("console")
    val encoder = assertIs<PatternLayoutEncoder>(assertIs<OutputStreamAppender<*>>(console).encoder)

    val forged = "clientId='dyn:abc\n2026-01-01 21:00:00,000 WARN  [jetty] forged'"
    val rendered = String(encoder.encode(event(encoder.context as LoggerContext, forged)))

    assertEquals(1, rendered.count { it == '\n' }, "the encoder's own line break is the only one: $rendered")
    assertTrue("dyn:abc" in rendered, rendered)
    assertTrue("\\n2026-01-01" in rendered, "the break is kept visible rather than dropped: $rendered")
  }

  @Test
  fun `every terminator a viewer might break on is caught, not only CR and LF`() {
    // The seven Unicode line terminators, and the reason the class is not just CR and LF: NEL and
    // the two separators are not whitespace to `Character`, they survive a URI check that rejects
    // CR and LF, and log tooling breaks a line on them anyway. VT and FF terminate a line too.
    val console = configure("LOG_FORMAT" to "console").getLogger(ROOT_LOGGER_NAME).getAppender("console")
    val encoder = assertIs<PatternLayoutEncoder>(assertIs<OutputStreamAppender<*>>(console).encoder)

    val terminators = listOf('\n', '\u000B', '\u000C', '\r', '\u0085', '\u2028', '\u2029')
    val rendered = String(encoder.encode(event(encoder.context as LoggerContext, terminators.joinToString(""))))

    terminators.forEach { assertTrue(it !in rendered.dropLast(1), "U+%04X reached the line: %s".format(it.code, rendered)) }
    assertEquals("\\n".repeat(terminators.size), rendered.substringAfterLast(" - ").dropLast(1), rendered)
  }

  private fun event(context: LoggerContext, message: String): LoggingEvent =
    LoggingEvent(
      Logger::class.java.name,
      context.getLogger("test"),
      Level.INFO,
      message,
      null,
      null,
    )

  @Test
  fun `LOG_LEVEL sets the root level`() {
    assertEquals(Level.DEBUG, configure("LOG_LEVEL" to "DEBUG").getLogger(ROOT_LOGGER_NAME).level)
    assertEquals(Level.WARN, configure("LOG_LEVEL" to "WARN").getLogger(ROOT_LOGGER_NAME).level)
  }

  @Test
  fun `LOG_FORMAT selects the encoder`() {
    val console = configure("LOG_FORMAT" to "console").getLogger(ROOT_LOGGER_NAME).getAppender("console")
    val pattern = assertIs<PatternLayoutEncoder>(assertIs<OutputStreamAppender<*>>(console).encoder).pattern
    assertTrue("%X" in pattern, "the console pattern must render the MDC, or the trace id is invisible: $pattern")

    val jsonContext = configure("LOG_FORMAT" to "json")
    assertEquals(listOf("json"), jsonContext.rootAppenderNames())
    val json = assertNotNull(jsonContext.getLogger(ROOT_LOGGER_NAME).getAppender("json"))
    assertIs<JsonEncoder>(assertIs<OutputStreamAppender<*>>(json).encoder)
  }

  @Test
  fun `the loud third-party loggers are pinned above the root level`() {
    val context = configure("LOG_LEVEL" to "DEBUG")
    assertEquals(Level.WARN, context.getLogger("org.mongodb.driver").level)
    assertEquals(Level.INFO, context.getLogger("org.eclipse.jetty").level)
    assertEquals(Level.INFO, context.getLogger("io.netty").level)
  }
}
