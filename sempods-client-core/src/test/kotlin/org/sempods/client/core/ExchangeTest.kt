package org.sempods.client.core

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.ConnectionOptions.connectionOptions
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * The execution every endpoint operation shares: how much of a body it reads, what it keeps of a
 * failure, and that it gives its admission slot back however it ends.
 */
class ExchangeTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun get(path: String = "x") =
    SempodsSession(SempodsPodBase.of("$origin/alice")).newRequest("GET", path).build()

  private fun serve(status: Int, body: String) {
    server.reset()
    server.`when`(request()).respond(response().withStatusCode(status).withBody(body))
  }

  @Test
  fun `a refused answer keeps at most 4 KiB of its body, and a character cut at the limit is replaced`() {
    // One byte, then two-byte characters, so the limit falls inside one of them.
    serve(500, "a" + "é".repeat(40 * 1024))

    val failure = assertThrows<SempodsStatusException> { Exchange(client).run(get(), emptySet(), BodyReading.TEXT) }

    assertEquals("a" + "é".repeat(2047) + "\uFFFD", failure.bodyExcerpt)
  }

  @Test
  fun `a failure names the method, the pod's URL without its query, and the status`() {
    serve(500, "boom")

    val failure = assertThrows<SempodsStatusException> {
      Exchange(client).run(get("x?token=abc"), emptySet(), BodyReading.TEXT)
    }

    assertEquals("GET $origin/alice/x answered 500, which this operation does not accept.", failure.message)
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `a body over the limit is refused whether or not its length was declared`(undeclared: Boolean) {
    server.`when`(request()).respond(
      response().withStatusCode(200).withHeader("X-Trace", "t").withBody("x".repeat(65))
        .withConnectionOptions(connectionOptions().withSuppressContentLengthHeader(undeclared).withCloseSocket(undeclared)),
    )

    val failure = assertThrows<SempodsDecodingException> {
      Exchange(client, maxBodyBytes = 64).run(get(), emptySet(), BodyReading.TEXT)
    }

    assertEquals(200, failure.status)
    assertEquals("t", failure.headers["X-Trace"])
  }

  @Test
  fun `a body at the limit is read whole`() {
    serve(200, "x".repeat(64))

    assertEquals("x".repeat(64), Exchange(client, maxBodyBytes = 64).run(get(), emptySet(), BodyReading.TEXT).body)
  }

  @ParameterizedTest
  @ValueSource(ints = [304, 412])
  fun `a listed answer comes back without a body and with its headers`(status: Int) {
    server.`when`(request()).respond(response().withStatusCode(status).withHeader("ETag", "\"v7\""))

    val answer = Exchange(client).run(get(), setOf(304, 412), BodyReading.TEXT)

    assertEquals(status, answer.status)
    assertEquals("\"v7\"", answer.headers["ETag"])
    assertNull(answer.body)
  }

  @Test
  fun `a deadline that runs out is the network's failure rather than an answer's`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("late").withDelay(TimeUnit.SECONDS, 2))

    sempodsClient { callTimeout(Duration.ofMillis(200)) }.closing { impatient ->
      val failure = assertThrows<IOException> { Exchange(impatient).run(get(), emptySet(), BodyReading.TEXT) }
      assertFalse(failure is SempodsResponseException, "$failure")
    }
  }

  @Test
  fun `every way an operation ends gives its admission slot back`() {
    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { narrow ->
      val exchange = Exchange(narrow, maxBodyBytes = 64)
      val objectReading = BodyReading<ProtocolObject> { bytes, _ -> decodeObject(bytes) }

      serve(200, "{}")
      exchange.run(get(), emptySet(), BodyReading.TEXT)
      serve(404, "gone")
      exchange.run(get(), setOf(404), BodyReading.TEXT)
      serve(500, "boom")
      assertThrows<SempodsStatusException> { exchange.run(get(), emptySet(), BodyReading.TEXT) }
      serve(200, "[]")
      assertThrows<SempodsDecodingException> { exchange.run(get(), emptySet(), objectReading) }
      serve(200, "x".repeat(65))
      assertThrows<SempodsDecodingException> { exchange.run(get(), emptySet(), BodyReading.TEXT) }
      serve(200, "never read")
      assertEquals(200, exchange.status(get(), emptySet()))

      // With one slot and no queue, any of the above that kept its slot would refuse this call.
      serve(200, "last")
      assertEquals("last", exchange.run(get(), emptySet(), BodyReading.TEXT).body)
    }
  }
}
