package org.sempods.client.media

import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsSession
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * A pod that answers and then drops the connection, which is what an idle timeout on the far side
 * does to a connection this client still holds. MockServer keeps its connections open, so it cannot
 * stage this — and a `POST` is the case that needs staging: it is resent on the repeatable mark
 * rather than on its method.
 */
class SempodsPodMediaResendTest {

  private lateinit var server: ServerSocket
  private val requests = AtomicInteger()
  private val opened = AtomicInteger()

  @BeforeEach
  fun start() {
    server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val socket = try {
          server.accept()
        } catch (closed: IOException) {
          return@thread
        }
        thread(isDaemon = true) { socket.use { readRequest(it)?.let { method -> answer(it, method) } } }
      }
    }
  }

  @AfterEach
  fun stop() = server.close()

  /** Reads one request head and its body, and answers with the method that arrived, or null. */
  private fun readRequest(socket: Socket): String? {
    val input = BufferedInputStream(socket.getInputStream())
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
      val next = input.read()
      if (next < 0) return null
      head.append(next.toChar())
    }
    requests.incrementAndGet()
    val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
    repeat(length) { if (input.read() < 0) return null }
    return head.toString().substringBefore(' ')
  }

  /** An upload is answered as the route answers one; an assignment carries no body. */
  private fun answer(socket: Socket, method: String) {
    val response = if (method == "POST") {
      val body = """{"id":"abc","content_url":"https://pods.example/abc/content"}"""
      "HTTP/1.1 201 Created\r\nContent-Length: ${body.length}\r\n\r\n$body"
    } else {
      "HTTP/1.1 204 No Content\r\n\r\n"
    }
    socket.getOutputStream().write(response.toByteArray())
    socket.getOutputStream().flush()
  }

  private fun media(client: OkHttpClient) =
    SempodsPodMedia(SempodsPod(SempodsSession(SempodsPodBase.of("http://127.0.0.1:${server.localPort}/alice")), client))

  /** One call, so the pool holds a connection the server has dropped by the time the next arrives. */
  private fun leaveAStaleConnection(client: OkHttpClient) {
    media(client).assign("first", "https://pods.example/alice/_system/contexts/tasks")
    Thread.sleep(100)
  }

  @Test
  fun `an upload is sent again on a connection the pod had already dropped`() {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    try {
      leaveAStaleConnection(client)
      requests.set(0)

      val stored = media(client).upload(
        "https://pods.example/alice/_system/contexts/tasks",
        "image/png",
        { opened.incrementAndGet(); "PNG".byteInputStream() },
        3,
      )

      assertEquals(201, stored.status)
      assertEquals(1, requests.get(), "the attempt on the dropped connection never reached the pod")
      assertEquals(2, opened.get(), "the source is opened for the attempt that was lost and for the resend")
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }

  @Test
  fun `a source upload is not sent again, because the fetch is the pod's to make`() {
    val client = SempodsOkHttp.install(OkHttpClient.Builder()).build()
    try {
      leaveAStaleConnection(client)

      assertThrows<IOException> {
        media(client).uploadFromUrl("https://pods.example/alice/_system/contexts/tasks", "https://drive.example/a")
      }
    } finally {
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
  }
}
