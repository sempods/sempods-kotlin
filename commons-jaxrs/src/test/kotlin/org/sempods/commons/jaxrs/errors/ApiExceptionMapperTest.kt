package org.sempods.commons.jaxrs.errors

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.NotAcceptableException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.UriInfo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.sempods.commons.jaxrs.ContainerRequestHolder
import org.sempods.commons.jaxrs.SecretPathSegment
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * That a logged failure says which request produced it.
 *
 * Asserted against the log line itself, because that is the whole subject — the mapper's return
 * value was never the problem. Twenty-nine `406`s in twenty-five hours of production could not be
 * attributed to a caller, and the reason was one missing string; `docs/logging.md` §"Three rules"
 * carries what a failure line owes its reader.
 *
 * The logger is process-wide, so nothing here mutates it beyond attaching an appender, and each
 * case reads only the line carrying its own marker. Everything asserted is logged at `WARN` or
 * `ERROR`, which `gradle/logback-test.xml` passes at its default root level.
 */
class ApiExceptionMapperTest {

  private val mapper = ApiExceptionMapper(emptySet())
  private val appender = ListAppender<ILoggingEvent>()

  private lateinit var logger: Logger

  @BeforeEach
  fun attachAppender() {
    logger = logbackContext().getLogger(ApiExceptionMapper::class.java)
    appender.start()
    logger.addAppender(appender)
  }

  @AfterEach
  fun detachAppender() {
    logger.detachAppender(appender)
    appender.stop()
    ContainerRequestHolder.clear()
  }

  /**
   * SLF4J hands a `SubstituteLoggerFactory` to every thread but the one currently binding the
   * provider, and this suite runs its classes in a `ForkJoinPool` — so the window is reachable and
   * a plain cast is flaky. It closes in microseconds.
   */
  private fun logbackContext(): LoggerContext {
    repeat(500) {
      val factory = LoggerFactory.getILoggerFactory()
      if (factory is LoggerContext) return factory
      Thread.sleep(10)
    }
    error("logback never became the SLF4J binding of this test JVM")
  }

  private fun bindRequest(method: String, path: String) {
    val uriInfo = mockk<UriInfo>(relaxed = true)
    every { uriInfo.path } returns path
    val requestContext = mockk<ContainerRequestContext>(relaxed = true)
    every { requestContext.method } returns method
    every { requestContext.uriInfo } returns uriInfo
    ContainerRequestHolder.set(requestContext)
  }

  /** The one line this case produced. [marker] is what tells it from a sibling's, if they overlap. */
  private fun loggedLine(marker: String): String =
    appender.list.map { it.formattedMessage }.single { marker in it }

  @Test
  fun `a failure decided during matching names the request`() {
    // The case the pre-matching carrier exists for: no resource method ran, so nothing but the
    // holder knows what was asked for.
    bindRequest("GET", "v1/pods/alice/events/42")

    mapper.toResponse(NotAcceptableException())

    assertTrue(
      loggedLine("events/42").endsWith(" [GET v1/pods/alice/events/42]"),
      "the line must name the request, was: ${loggedLine("events/42")}",
    )
  }

  @Test
  fun `an ApiException names the request beside its errors`() {
    bindRequest("POST", "v1/pods/alice/_system/auth/token")

    mapper.toResponse(ApiException(errorId = "invalid_grant", statusCode = 400))

    val line = loggedLine("invalid_grant")
    assertTrue(line.endsWith(" [POST v1/pods/alice/_system/auth/token]"), "was: $line")
  }

  @Test
  fun `an unknown exception names the request and still answers 500`() {
    bindRequest("DELETE", "v1/pods/alice")

    val response = mapper.toResponse(IllegalStateException("something gave up"))

    assertEquals(500, response.status)
    assertEquals("something gave up [DELETE v1/pods/alice]", loggedLine("something gave up"))
  }

  @Test
  fun `a declared secret segment never reaches the line`() {
    // A route that puts a usable share token in its URL: a 405 or 406 there has no matched
    // template to log instead of the concrete path, so the credential has to be replaced.
    val mapperWithDeclaration = ApiExceptionMapper(setOf(SecretPathSegment("join")))
    bindRequest("GET", "v1/rooms/42/join/sh4re-t0ken")

    mapperWithDeclaration.toResponse(NotAcceptableException())

    val line = loggedLine("rooms/42")
    assertFalse("sh4re-t0ken" in line, "the credential must not be logged, was: $line")
    assertTrue(line.endsWith(" [GET v1/rooms/42/join/<redacted>]"), "was: $line")
  }

  @Test
  fun `an undeclared path is logged whole`() {
    // Only the segment after a declared marker goes; nothing else about the path is guessed at.
    bindRequest("GET", "v1/rooms/43/join/sh4re-t0ken")

    mapper.toResponse(NotAcceptableException())

    assertTrue(loggedLine("rooms/43").endsWith(" [GET v1/rooms/43/join/sh4re-t0ken]"))
  }

  @Test
  fun `a path cannot forge a second log line`() {
    // Belt to `RequestPathForLogTest`'s braces: what matters here is that the mapper goes through
    // it at all, so a later edit interpolating the raw path fails rather than reopens this.
    bindRequest("GET", "v1/rooms/44\nERROR forged")

    mapper.toResponse(NotAcceptableException())

    val line = loggedLine("rooms/44")
    assertFalse('\n' in line, "was: $line")
    assertTrue(line.endsWith(" [GET v1/rooms/44\\u000aERROR forged]"), "was: $line")
  }

  @Test
  fun `outside a request the line is unchanged and nothing throws`() {
    // The mapper is reachable from a thread no filter ever touched; it has to log, not fail.
    val response = mapper.toResponse(IllegalStateException("no request here"))

    assertEquals(500, response.status)
    assertEquals("no request here", loggedLine("no request here"))
  }
}
