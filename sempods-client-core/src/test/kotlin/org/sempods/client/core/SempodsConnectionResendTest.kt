package org.sempods.client.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
import java.time.Duration
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
 * The one resend OkHttp used to make on its own, made by the session's interceptor instead.
 *
 * The server here answers and then drops the connection without saying so — what an idle timeout on
 * the far side does to a connection this client still holds in its pool. MockServer keeps its
 * connections open, so it cannot stage this.
 */
class SempodsConnectionResendTest {

  private lateinit var server: ServerSocket
  private val connections = AtomicInteger()
  private val requestHeads = CopyOnWriteArrayList<String>()

  /** What the server does with the n-th connection it accepts. By default: answer, then hang up. */
  @Volatile private var onConnection: (Socket, Int) -> Unit = { socket, _ -> answerAndHangUp(socket) }

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
        val index = connections.incrementAndGet()
        thread(isDaemon = true) {
          try {
            onConnection(socket, index)
          } catch (gone: IOException) {
            // The client gave up on a slow answer; there is nobody left to write it to.
          }
        }
      }
    }
  }

  @AfterEach
  fun stop() = server.close()

  /** Reads one request, head and body, and records the head. `false` when the client sent nothing. */
  private fun readRequest(socket: Socket): Boolean {
    val input = BufferedInputStream(socket.getInputStream())
    val head = StringBuilder()
    while (!head.endsWith("\r\n\r\n")) {
      val next = input.read()
      if (next < 0) return false
      head.append(next.toChar())
    }
    requestHeads += head.toString()
    val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
    repeat(length) { input.read() }
    return true
  }

  private fun answer(socket: Socket) {
    // No `Connection: close`: the client keeps the connection, and the server drops it anyway.
    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
    socket.getOutputStream().flush()
  }

  private fun answerAndHangUp(socket: Socket) {
    socket.use { if (readRequest(it)) answer(it) }
  }

  private fun session() =
    SempodsSession(SempodsPodBase.of("http://127.0.0.1:${server.localPort}/alice"), attemptHeader)

  /** One request, so the pool holds a connection the server has dropped by the time the next arrives. */
  private fun leaveAStaleConnection(client: OkHttpClient, session: SempodsSession) {
    client.newCall(session.newRequest("GET", "first").build()).execute().use { assertEquals(200, it.code) }
    Thread.sleep(100)
  }

  @Test
  fun `a stale pooled connection is resent once for an idempotent method, authenticated afresh`() {
    sempodsClient().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      client.newCall(a.newRequest("PUT", "second").put("x".toRequestBody()).build()).execute()
        .use { assertEquals(200, it.code) }

      assertEquals(2, connections.get())
      assertEquals(2, requestHeads.size, "the attempt on the dropped connection never reached the server")
      assertTrue(requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `a POST on a stale pooled connection is not resent`() {
    // The client below is OkHttp's default, whose own `retryOnConnectionFailure` is on. The session's
    // interceptor switches it off for its call, so what is seen here is the session's rule.
    sempodsClient().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      assertThrows<IOException> {
        client.newCall(a.newRequest("POST", "second").post("x".toRequestBody()).build()).execute().close()
      }
      assertEquals(1, requestHeads.size)
    }
  }

  @Test
  fun `a POST marked repeatable is resent like an idempotent request`() {
    sempodsClient().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      val query = SempodsRepeatable.mark(a.newRequest("POST", "sparql").post("SELECT * { ?s ?p ?o }".toRequestBody()))
      client.newCall(query.build()).execute().use { assertEquals(200, it.code) }

      assertEquals(2, requestHeads.size)
      assertTrue(requestHeads[1].startsWith("POST") && requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `a SPARQL query through the pod's group is resent once, as the repeatable request it is`() {
    sempodsClient().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      val answer = SempodsPod(a, client).sparql().resultsJson("ASK {}", SempodsContextSelection.none())

      assertEquals(200, answer.status)
      assertEquals(2, requestHeads.size)
      assertTrue(requestHeads[1].startsWith("POST /alice/_system/sparql/query?default-graph-uri= "), requestHeads[1])
      assertTrue(requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `the repeatable mark holds when an interceptor ahead rebuilds the request without its tags`() {
    val rebuilding = Interceptor { chain ->
      val original = chain.request()
      chain.proceed(Request.Builder().url(original.url).headers(original.headers).method(original.method, original.body).build())
    }
    val builder = SempodsOkHttp.install(OkHttpClient.Builder())
    builder.interceptors().add(0, rebuilding)

    builder.build().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      val query = SempodsRepeatable.mark(a.newRequest("POST", "sparql").post("SELECT * { ?s ?p ?o }".toRequestBody()))
      client.newCall(query.build()).execute().use { assertEquals(200, it.code) }

      assertEquals(2, requestHeads.size)
      assertTrue(requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])
    }
  }

  @Test
  fun `a one-shot body is not resent, even for an idempotent method`() {
    val oneShot = oneShotBody("x")
    sempodsClient().closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      assertThrows<IOException> { client.newCall(a.newRequest("PUT", "second").put(oneShot).build()).execute().close() }
      assertEquals(1, requestHeads.size)
    }
  }

  @Test
  fun `the call deadline spans the resend`() {
    // An odd connection takes the request and is dropped 400 ms later without an answer; an even one
    // answers 400 ms after the request arrived. Each attempt is inside the deadline, the two
    // together are not.
    onConnection = { socket, index ->
      socket.use {
        if (readRequest(it)) {
          Thread.sleep(400)
          if (index % 2 == 0) answer(it)
        }
      }
    }
    val a = session()

    sempodsClient { callTimeout(Duration.ofMillis(650)) }.closing { client ->
      val ended = assertThrows<InterruptedIOException> {
        client.newCall(a.newRequest("GET", "x").build()).execute().close()
      }
      assertEquals("timeout", ended.message)
    }
    assertEquals(2, requestHeads.size, "the resend never started")
    assertTrue(requestHeads[1].contains("X-Attempt: 2"), requestHeads[1])

    // With room for both, the same exchange succeeds: what ended the call above was the deadline.
    sempodsClient { callTimeout(Duration.ofSeconds(5)) }.closing { client ->
      client.newCall(a.newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }
    assertEquals(4, requestHeads.size)
  }

  @Test
  fun `cancelling after a lost connection starts no resend`() {
    // The attempt on the dropped connection fails, and the call is cancelled right there — before the
    // session's interceptor decides whether to send it again.
    val cancelOnFailure = object : EventListener() {
      override fun requestFailed(call: Call, ioe: IOException) = call.cancel()

      override fun responseFailed(call: Call, ioe: IOException) = call.cancel()
    }
    sempodsClient { eventListener(cancelOnFailure) }.closing { client ->
      val a = session()
      leaveAStaleConnection(client, a)

      val call = client.newCall(a.newRequest("PUT", "second").put("x".toRequestBody()).build())
      assertThrows<IOException> { call.execute().close() }

      assertTrue(call.isCanceled())
      assertEquals(1, connections.get(), "a resend opened a second connection")
      assertEquals(1, requestHeads.size)
    }
  }

  @Test
  fun `a 503 asking for an immediate retry goes out once and reaches the caller`() {
    // OkHttp repeats a `503` with `Retry-After: 0` by itself, below the session's interceptor.
    onConnection = { socket, _ ->
      socket.use {
        if (readRequest(it)) {
          it.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 0\r\nContent-Length: 0\r\n\r\n".toByteArray())
          it.getOutputStream().flush()
        }
      }
    }
    sempodsClient().closing { client ->
      val post = session().newRequest("POST", "x").post("x".toRequestBody()).build()
      client.newCall(post).execute().use { assertEquals(503, it.code) }
    }
    assertEquals(1, requestHeads.size, "the POST went out twice")
  }

  @Test
  fun `an authentication retry on a dropped pooled connection gets the resend too`() {
    // The refusal arrives on the first connection, which the server then drops; the retry meets it.
    onConnection = { socket, index ->
      socket.use {
        if (readRequest(it)) {
          val status = if (index == 1) "401 Unauthorized" else "200 OK"
          it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
          it.getOutputStream().flush()
        }
      }
    }
    val retrying = object : SempodsRequestAuth {
      override fun apply(request: Request.Builder, attempt: Int) {
        request.header("X-Attempt", "$attempt")
      }

      override fun recover(response: Response, attempt: Int) = response.code == 401
    }
    val a = SempodsSession(SempodsPodBase.of("http://127.0.0.1:${server.localPort}/alice"), retrying)

    sempodsClient().closing { client ->
      client.newCall(a.newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }
    assertEquals(2, requestHeads.size)
    assertTrue(requestHeads[1].contains("X-Attempt: 3"), requestHeads[1])
  }

  @Test
  fun `a resend's credential fetched through the same client runs on the call's slot`() {
    // The mechanism fetches through the same client for the resend; a token answer closes its connection.
    onConnection = { socket, _ ->
      socket.use {
        if (readRequest(it)) {
          val close = if (requestHeads.last().startsWith("GET /token")) "Connection: close\r\n" else ""
          it.getOutputStream().write("HTTP/1.1 200 OK\r\n${close}Content-Length: 2\r\n\r\nok".toByteArray())
          it.getOutputStream().flush()
        }
      }
    }

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 4)) { callTimeout(Duration.ofSeconds(5)) }.closing { client ->
      val fetching = SempodsRequestAuth { request, attempt ->
        if (attempt > 1) client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/token").build()).execute().close()
        request.header("X-Attempt", "$attempt")
      }
      val a = SempodsSession(SempodsPodBase.of("http://127.0.0.1:${server.localPort}/alice"), fetching)
      leaveAStaleConnection(client, a)

      client.newCall(a.newRequest("PUT", "second").put("x".toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
    }
    assertTrue(requestHeads.any { it.startsWith("GET /token") }, requestHeads.toString())
  }

  @Test
  fun `a failing authentication is not resent as a lost connection`() {
    val asked = AtomicInteger()
    val failing = SempodsRequestAuth.refreshable(
      SempodsCredentialSupplier { asked.incrementAndGet(); throw IOException("token endpoint answered 500") },
    )
    val a = SempodsSession(SempodsPodBase.of("http://127.0.0.1:${server.localPort}/alice"), failing)

    sempodsClient().closing { client ->
      assertThrows<IOException> { client.newCall(a.newRequest("GET", "x").build()).execute().close() }
    }
    assertEquals(1, asked.get())
    assertEquals(0, connections.get())
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
    notResent.forEach { failure -> assertFalse(ConnectionResend.allowed(failure, get, repeatable = false), "$failure") }
    assertTrue(ConnectionResend.allowed(SocketException("Connection reset"), get, repeatable = false))
  }
}
