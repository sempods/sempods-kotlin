package org.sempods.commons.jaxrs

import jakarta.ws.rs.core.EntityTag
import jakarta.ws.rs.core.Response
import org.glassfish.jersey.internal.MapPropertiesDelegate
import org.glassfish.jersey.server.ContainerRequest
import org.junit.jupiter.api.AfterEach
import org.sempods.commons.logging.CapturedLog
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [BaseEndpoint.evaluatePreconditions] writes down when a request sends a header it cannot
 * parse.
 *
 * Against a real [ContainerRequest] rather than a mock, because the whole subject is what *Jersey*
 * puts in that message: `HeaderValueException` quotes the offending header value verbatim, so the
 * line is the request's own text one indirection out. `docs/logging.md` §"Three rules".
 */
class BaseEndpointPreconditionTest {

  private val endpoint = object : BaseEndpoint() {
    fun evaluate(tag: EntityTag): Response? = evaluatePreconditions(tag)
  }

  @AfterEach
  fun clearRequest() = ContainerRequestHolder.clear()

  private fun bind(ifNoneMatch: String) {
    val request = ContainerRequest(
      URI.create("http://x/"), URI.create("http://x/r"), "GET",
      null, MapPropertiesDelegate(), null,
    )
    request.header("If-None-Match", ifNoneMatch)
    ContainerRequestHolder.set(request)
  }

  @Test
  fun `an unparseable If-None-Match cannot forge a second log line`() {
    bind("\"abc\n2026-01-01 21:00:00,000 WARN  [jetty] pod deleted by admin")

    val lines = CapturedLog.linesFrom(BaseEndpoint::class.java) {
      assertNull(endpoint.evaluate(EntityTag("v1")), "an unreadable precondition is not a 412")
    }

    val line = lines.single { "Failed to evaluate preconditions" in it }
    assertFalse('\n' in line, "the line carries a raw newline: $line")
    assertTrue("\\u000a" in line, line)
  }

  @Test
  fun `the Unicode line separator is escaped too, which no URI check would have caught`() {
    // A header value, not a path: nothing between the socket and this line rejects U+2028.
    bind("\"abc\u2028forged")

    val lines = CapturedLog.linesFrom(BaseEndpoint::class.java) {
      endpoint.evaluate(EntityTag("v1"))
    }

    assertTrue("\\u2028" in lines.single { "Failed to evaluate preconditions" in it })
  }

  @Test
  fun `a readable precondition is unaffected`() {
    bind("\"v1\"")

    // A matching tag is a 304, which is a response rather than a refusal to read the header.
    assertTrue(CapturedLog.linesFrom(BaseEndpoint::class.java) { endpoint.evaluate(EntityTag("v1")) }.isEmpty())
  }
}
