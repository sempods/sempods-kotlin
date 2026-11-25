package org.sempods.pods.oauth.didweb

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.sempods.client.net.SsrfBlockedException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fetch a `did:web` document takes: a server on loopback, which the guard lets through only
 * where private addresses are allowed, as in development.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkHttpDidDocumentFetcherTest {

  private lateinit var server: HttpServer
  private val base get() = "http://localhost:${server.address.port}"

  @BeforeAll
  fun start() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
      handle("/svc/did.json") { it.send(200, """{"id":"did:web:localhost%3A${address.port}:svc"}""") }
      handle("/missing/did.json") { it.send(404, "") }
      handle("/large/did.json") { it.send(200, "x".repeat(OkHttpDidDocumentFetcher.MAX_BODY_BYTES.toInt() + 1)) }
      handle("/moved/did.json") { exchange ->
        exchange.responseHeaders.add("Location", "$base/svc/did.json")
        exchange.send(302, "")
      }
      start()
    }
  }

  @AfterAll
  fun stop() = server.stop(0)

  private val development = OkHttpDidDocumentFetcher(OkHttpClient(), allowPrivateAddresses = true)

  @Test
  fun `a document is read`() {
    assertTrue(development.fetch("$base/svc/did.json").contains("\"id\""))
  }

  @Test
  fun `anything but a 200 is a failure, a redirect included`() {
    assertThrows<IOException> { development.fetch("$base/missing/did.json") }
    assertThrows<IOException> { development.fetch("$base/moved/did.json") }
  }

  @Test
  fun `an oversized document is refused`() {
    val refused = assertThrows<IOException> { development.fetch("$base/large/did.json") }
    assertTrue("exceeds" in refused.message.orEmpty(), refused.message)
  }

  @Test
  fun `outside development a loopback address is not dialled`() {
    val production = OkHttpDidDocumentFetcher(OkHttpClient(), allowPrivateAddresses = false)

    val refused = assertThrows<IOException> { production.fetch("$base/svc/did.json") }
    assertTrue(generateSequence<Throwable>(refused) { it.cause }.any { it is SsrfBlockedException }, "refused by the guard: $refused")
  }

  private fun HttpServer.handle(path: String, handler: (HttpExchange) -> Unit) {
    createContext(path) { exchange -> exchange.use(handler) }
  }

  private fun HttpExchange.send(status: Int, body: String) {
    val bytes = body.toByteArray()
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
  }
}
