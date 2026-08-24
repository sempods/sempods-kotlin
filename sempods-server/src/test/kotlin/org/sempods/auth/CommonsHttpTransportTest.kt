package org.sempods.auth

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The pod server's leg to the identity service: the deadline it runs under, and what it does when
 * that deadline expires.
 *
 * The deadline is pinned because `docs/auth/oauth.md` §"Sharp edges" quotes it, and that
 * paragraph has now described this leg wrongly twice: first as "10 s with two retries" — the right
 * number attributed to the wrong layer, and a retry count that exists nowhere — then as the 60 s
 * request budget of the client underneath, which this wrapper never lets anything reach. Measuring
 * the library and missing the wrapper over it is the specific mistake this test exists to catch.
 *
 * Not an assertion that ten seconds is right. It is the one timeout on either OIDC leg that
 * somebody actually chose, which is a low bar — see the TODO on `OidcTokenExchange`.
 */
class CommonsHttpTransportTest {

  private lateinit var server: HttpServer
  private var status = 200
  private var responseBody = """{"ok":true}"""
  private var receivedBody: String? = null
  private var receivedContentType: String? = null
  private var stall = false

  @BeforeEach
  fun startServer() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/") { exchange ->
      receivedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
      receivedContentType = exchange.requestHeaders.getFirst("Content-Type")
      if (stall) {
        // Never answers. The client's own deadline is the only thing that ends this exchange.
        Thread.sleep(STALL.toMillis())
      }
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
  fun `the login leg gives up after ten seconds, well inside the shared client's budgets`() {
    assertEquals(Duration.ofSeconds(10), CommonsHttpTransport.TIMEOUT)
  }

  /**
   * Waiting less than the HTTP client allows frees the thread; it does not free the request —
   * which is the hazard this leg has to close rather than merely wait out. OkHttp's
   * `callTimeout` closes the socket and lets the blocking send fail with an
   * [InterruptedIOException], so an abandoned login stops costing the id-server a connection at the
   * moment the caller stops waiting rather than a minute later.
   *
   * The exception type is what says which deadline fired: a socket-idle timeout is a
   * `SocketTimeoutException`, and it would leave the call running.
   */
  @Test
  fun `giving up on the wait gives up on the request`() {
    stall = true
    val transport = CommonsHttpTransport(OkHttpClient(), DEADLINE)

    val start = System.nanoTime()
    val failure = assertFailsWith<InterruptedIOException> { transport.get(url()) }
    val elapsed = Duration.ofNanos(System.nanoTime() - start)

    assertEquals("timeout", failure.message, "the whole-call deadline fired, not a socket-idle one")
    assertTrue(elapsed < STALL, "the call must end on its own deadline, not on the server's answer")
  }

  @Test
  fun `get refuses a non-200`() {
    status = 503

    assertFailsWith<IllegalStateException> { transport().get(url()) }
  }

  @Test
  fun `postForm sends the pairs url-encoded`() {
    transport().postForm(url(), mapOf("grant_type" to "authorization_code", "code" to "a code/with symbols"))

    assertEquals("application/x-www-form-urlencoded", receivedContentType)
    assertEquals(
      mapOf("grant_type" to "authorization_code", "code" to "a code/with symbols"),
      formParams(checkNotNull(receivedBody)),
    )
  }

  /** An OAuth error is a JSON document with a reason in it; throwing before it is read loses it. */
  @Test
  fun `postForm returns the body on a 4xx`() {
    status = 400
    responseBody = """{"error":"invalid_grant"}"""

    assertEquals(responseBody, transport().postForm(url(), mapOf("grant_type" to "authorization_code")))
  }

  private fun transport() = CommonsHttpTransport(OkHttpClient(), DEADLINE)

  private fun url() = "http://${server.address.hostString}:${server.address.port}/"

  private fun formParams(body: String): Map<String, String> =
    body.split('&').associate { pair ->
      val (name, value) = pair.split('=', limit = 2)
      URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
    }

  private companion object {
    /** Short, so the suite does not wait out the production deadline to observe what it does. */
    val DEADLINE: Duration = Duration.ofMillis(300)

    /** Long enough that a call ending inside it ended on [DEADLINE] and nothing else. */
    val STALL: Duration = Duration.ofSeconds(5)
  }
}
