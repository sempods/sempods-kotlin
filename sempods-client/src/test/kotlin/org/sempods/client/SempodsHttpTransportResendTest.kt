package org.sempods.client

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The resend this surface keeps.
 *
 * The core switches OkHttp's own resend off for a session's call and decides the resend itself.
 * This surface does not go through a session, so without OkHttp's resend a pooled connection the
 * server had closed would fail every caller that still speaks it — including the POSTs it sent
 * before the core existed.
 */
class SempodsHttpTransportResendTest {

  private lateinit var server: ServerSocket
  private val connections = AtomicInteger()

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
      val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
      repeat(length) { input.read() }
      // No `Connection: close`: the client keeps the connection, and the server drops it anyway.
      it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
      it.getOutputStream().flush()
    }
  }

  @Test
  fun `a pooled connection the server closed is bridged, a POST included`() {
    val transport = SempodsHttpTransport()
    val uri = URI("http://127.0.0.1:${server.localPort}/alice/x")

    assertEquals(200, transport.send(transport.newRequest(uri).GET().build()).statusCode)
    Thread.sleep(100)
    assertEquals(200, transport.send(transport.newRequest(uri).POST(SempodsBody.text("x")).build()).statusCode)

    assertEquals(2, connections.get())
  }
}
