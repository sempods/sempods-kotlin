package org.sempods.client.core

import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** What `install` decides for the builder it is handed, and what it leaves to the consumer. */
class SempodsOkHttpInstallTest {

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
  fun `a zero deadline set after install lifts it for a long-lived stream`() {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).callTimeout(Duration.ZERO).build()

    assertEquals(0, client.callTimeoutMillis)
  }

  @Test
  fun `a session's request carries the published placeholder host`() {
    val session = SempodsSession(SempodsPodBase.of("https://pods.example/alice"))

    assertEquals(SempodsOkHttp.UNBOUND_HOST, session.newRequest("GET", "_system/contexts").build().url.host)
  }
}
