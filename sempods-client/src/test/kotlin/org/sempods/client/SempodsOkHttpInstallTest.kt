package org.sempods.client

import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** What `install` decides for the builder it is handed, and what it leaves to the consumer. */
class SempodsOkHttpInstallTest : MockPodTest() {

  private fun slow() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("late").withDelay(TimeUnit.SECONDS, 3))
  }

  private fun OkHttpClient.read() = SempodsForeignTarget(this).getText("$origin/slow", "text/turtle")

  @Test
  fun `a builder without a call deadline gets two minutes`() {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()

    assertEquals(Duration.ofMinutes(2).toMillis(), client.callTimeoutMillis.toLong())
  }

  @Test
  fun `a deadline the builder already carries stays`() {
    val client = SempodsOkHttp.install(OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10))).build()

    assertEquals(10_000, client.callTimeoutMillis)
  }

  @Test
  fun `the deadline bounds an answer the read timeout would tolerate`() {
    // The distinction the default exists for: `read` only ever measures the gap between two bytes,
    // so a server answering slowly — or dripping just inside that gap — never trips it. Only the
    // whole-call deadline bounds the total.
    slow()
    val client = SempodsOkHttp.install(OkHttpClient.Builder().readTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(1))).build()

    client.closing {
      val elapsed = measureTimeMillis { assertThrows<IOException> { it.read() } }
      assertTrue(elapsed < 2_500, "the call deadline did not fire; waited ${elapsed}ms")
    }
  }

  @Test
  fun `a zero deadline set after install lifts it for a long-lived stream`() {
    // A context export streams a whole graph and must not be cut off by elapsed time. Set before
    // `install`, zero reads as "none" and earns the default instead — which is why this is after.
    slow()
    val client = SempodsOkHttp.install(OkHttpClient.Builder().readTimeout(Duration.ofSeconds(10))).callTimeout(Duration.ZERO).build()

    assertEquals(0, client.callTimeoutMillis)
    client.closing { assertEquals("late", it.read().body) }
  }

  @Test
  fun `a session's request carries the published placeholder host`() {
    val session = SempodsSession(SempodsPodBase.of("https://pods.example/alice"))

    assertEquals(SempodsOkHttp.UNBOUND_HOST, session.newRequest("GET", "_system/contexts").build().url.host)
  }
}
