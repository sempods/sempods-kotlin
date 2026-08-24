package org.sempods.commons.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
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
