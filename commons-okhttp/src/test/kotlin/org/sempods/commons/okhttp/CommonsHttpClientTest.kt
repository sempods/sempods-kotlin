package org.sempods.commons.okhttp

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommonsHttpClientTest {

  private lateinit var server: HttpServer
  private var requestBody: String? = null
  private var requestContentType: String? = null
  private var status = 200
  private var responseBody = """{"ok":true}"""

  private val httpClient = CommonsHttpClient(OkHttpClient())

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
      requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
      requestContentType = exchange.requestHeaders.getFirst("Content-Type")
      val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    server.start()
  }

  @AfterEach
  fun stopServer() = server.stop(0)

  @Test
  fun `a payload goes out as json, with the exact media type`() {
    httpClient.execute(httpClient.prepareJsonPost(url(), mapOf("model" to "m")), 200)

    assertEquals("""{"model":"m"}""", requestBody)
    // Not `application/json; charset=utf-8`: an API matching on the exact type would see a header
    // this project never sent, which is why the body declares no content type of its own.
    assertEquals("application/json", requestContentType)
  }

  @Test
  fun `a listed status returns the body`() {
    status = 201

    assertEquals(responseBody, httpClient.execute(httpClient.prepareJsonPost(url(), emptyMap<String, Any>()), 200, 201))
  }

  @Test
  fun `an unlisted status fails with the status and the server's own body`() {
    status = 429
    responseBody = """{"error":"rate limited"}"""

    val failure = assertFailsWith<HttpResponseException> {
      httpClient.execute(httpClient.prepareJsonPost(url(), emptyMap<String, Any>()), 200)
    }

    assertEquals(429, failure.statusCode)
    assertEquals(responseBody, failure.responseBody, "the reason travels with the refusal")
  }

  @Test
  fun `naming no status leaves every response to the caller`() {
    status = 500

    assertEquals(responseBody, httpClient.execute(httpClient.prepareJsonPost(url(), emptyMap<String, Any>())))
  }

  private fun url() = "http://${server.address.hostString}:${server.address.port}/"
}
