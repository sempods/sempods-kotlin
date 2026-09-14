package org.sempods.client.core

import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The one resend OkHttp used to make on its own, now made by the session.
 *
 * The server here answers and then drops the connection without saying so — what an idle timeout on
 * the far side does to a connection this client still holds in its pool. MockServer keeps its
 * connections open, so it cannot stage this.
 */
class SempodsConnectionResendTest {

  private lateinit var server: ServerSocket
  private val connections = AtomicInteger()
  private val requestHeads = CopyOnWriteArrayList<String>()

  /** Each attempt's number on the wire, so a resend can be told apart from the request it repeats. */
  private val attemptHeader = SempodsRequestAuth { request, attempt -> request.header("X-Attempt", "$attempt") }

  @BeforeEach
  fun start() {
    server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (closed: IOException) {
          break
        }
        connections.incrementAndGet()
        thread(isDaemon = true) { answerAndHangUp(socket) }
      }
    }
  }

  @AfterEach
  fun stop() = server.close()

  private fun answerAndHangUp(socket: Socket) {
    socket.use {
      val input = BufferedInputStream(it.getInputStream())
      val head = StringBuilder()
      while (!head.endsWith("\r\n\r\n")) {
        val next = input.read()
        if (next < 0) return
        head.append(next.toChar())
      }
      requestHeads += head.toString()
      val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
      repeat(length) { input.read() }
      // No `Connection: close`: the client keeps the connection, and the server drops it anyway.
      it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
      it.getOutputStream().flush()
    }
  }

  private fun session(transport: SempodsTransport) =
    SempodsSession.builder("http://127.0.0.1:${server.localPort}/alice").transport(transport).auth(attemptHeader).build()

  /** One request, so the pool holds a connection the server has dropped by the time the next arrives. */
  private fun leaveAStaleConnection(session: SempodsSession) {
    session.execute(session.newRequest("GET", "first").build()).use { assertEquals(200, it.code) }
    Thread.sleep(100)
  }

  @Test
  fun `a stale pooled connection is resent once for an idempotent method, authenticated afresh`() {
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      leaveAStaleConnection(a)

      a.execute(a.newRequest("PUT", "second").put("x".toRequestBody()).build()).use { assertEquals(200, it.code) }

      assertEquals(2, connections.get())
      assertEquals(2, requestHeads.size, "the attempt on the dropped connection never reached the server")
      assertTrue(requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `a POST on a stale pooled connection is not resent`() {
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      leaveAStaleConnection(a)

      assertThrows<IOException> {
        a.execute(a.newRequest("POST", "second").post("x".toRequestBody()).build()).close()
      }
      assertEquals(1, requestHeads.size)
    }
  }

  @Test
  fun `a POST marked repeatable is resent like an idempotent request`() {
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      leaveAStaleConnection(a)

      val query = SempodsRepeatable.mark(a.newRequest("POST", "sparql").post("SELECT * { ?s ?p ?o }".toRequestBody()))
      a.execute(query.build()).use { assertEquals(200, it.code) }

      assertEquals(2, requestHeads.size)
      assertTrue(requestHeads[1].startsWith("POST") && requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `a one-shot body is not resent, even for an idempotent method`() {
    val oneShot = object : RequestBody() {
      override fun contentType() = null
      override fun isOneShot() = true
      override fun writeTo(sink: BufferedSink) {
        sink.writeUtf8("x")
      }
    }
    SempodsTransport.builder().build().use { transport ->
      val a = session(transport)
      leaveAStaleConnection(a)

      assertThrows<IOException> { a.execute(a.newRequest("PUT", "second").put(oneShot).build()).close() }
      assertEquals(1, requestHeads.size)
    }
  }

  @Test
  fun `a deadline, a refused connection or a refusal of this library's own is not resent`() {
    val get = Request.Builder().url("http://127.0.0.1/alice/x").build()
    val notResent = listOf(
      SocketTimeoutException("read timed out"),
      InterruptedIOException("timeout"),
      ConnectException("Connection refused"),
      UnknownHostException("pods.example"),
      SSLHandshakeException("no trusted certificate"),
      SempodsClientException("refused"),
    )
    notResent.forEach { failure -> assertFalse(ConnectionResend.allowed(failure, get), "$failure") }
    assertTrue(ConnectionResend.allowed(SocketException("Connection reset"), get))
  }
}
