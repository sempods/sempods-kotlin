package org.sempods.testsupport

import kotlin.test.assertEquals
import okhttp3.Request
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * What one test leaves on the server is gone before the next. In order and on one thread, so the
 * second case always runs after the first, also under `-PtestMethodsConcurrent`.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Execution(ExecutionMode.SAME_THREAD)
class MockPodIsolationTest : MockPodTest() {

  private fun get(path: String) = client.newCall(Request.Builder().url("$origin$path").build()).execute().use { it.code }

  @Test
  @Order(1)
  fun `a test leaves an expectation and a recorded request behind`() {
    server.`when`(request().withPath("/alice")).respond(response().withStatusCode(204))

    assertEquals(204, get("/alice"))
    assertEquals(1, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  @Order(2)
  fun `the next test finds neither`() {
    assertEquals(0, server.retrieveRecordedRequests(request()).size, "no request recorded by the test before")
    assertEquals(0, server.retrieveActiveExpectations(request()).size, "no expectation set by the test before")
    assertEquals(404, get("/alice"))
  }
}
