package org.sempods.client

import com.sun.net.httpserver.HttpServer
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsSession
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** A call made on the same thread while another is in flight: both are bound to the slot, and the owning call stays cancellable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsCallSlotTest {

  private lateinit var server: HttpServer

  private val transport = SempodsHttpTransport()

  @BeforeAll
  fun startServer() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    // A body that keeps arriving for half a minute, a byte every 50 ms.
    server.createContext("/trickle") { exchange ->
      exchange.sendResponseHeaders(200, 0)
      try {
        exchange.responseBody.use { body ->
          repeat(600) {
            body.write('x'.code)
            body.flush()
            Thread.sleep(50)
          }
        }
      } catch (_: IOException) {
        // The client cancelled.
      }
    }
    server.createContext("/slow") { exchange ->
      Thread.sleep(30_000)
      exchange.sendResponseHeaders(204, -1)
      exchange.close()
    }
    // What a pod answers `exists()` from, kept waiting so a cancel has something to end.
    server.createContext("/alice/_system/meta/date-modified") { exchange ->
      Thread.sleep(30_000)
      exchange.sendResponseHeaders(204, -1)
      exchange.close()
    }
    server.createContext("/quick") { exchange ->
      exchange.sendResponseHeaders(204, -1)
      exchange.close()
    }
    server.start()
  }

  @AfterAll
  fun stopServer() = server.stop(0)

  private fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}$path")

  private fun get(path: String) = transport.newRequest(uri(path)).GET().build()

  /** Runs [work] with [slot] on a thread of its own, and completes with what it threw, or `null`. */
  private fun inSlot(slot: SempodsCallSlot, work: () -> Unit): CompletableFuture<Throwable?> {
    val ended = CompletableFuture<Throwable?>()
    thread {
      try {
        SempodsCallSlot.using(slot, work)
        ended.complete(null)
      } catch (failure: Throwable) {
        ended.complete(failure)
      }
    }
    return ended
  }

  /**
   * An endpoint group builds its own request and sends it through `transport.calls` rather than
   * `send`. The slot has to reach it there: without that, a cancel marks the slot and leaves the
   * socket blocked until a timeout, which is what the slot exists to avoid.
   */
  @Test
  fun `a cancel ends a call an endpoint group made`() {
    val slot = SempodsCallSlot()
    val pod = SempodsPod(SempodsSession(SempodsPodBase.of(uri("/alice").toString())), transport.calls)

    val ended = inSlot(slot) { pod.metadata().exists() }
    Thread.sleep(200)
    slot.cancel()

    val failure = ended.get(5, TimeUnit.SECONDS)
    assertTrue(failure is IOException, "the group's call ended with $failure")
  }

  @Test
  fun `a call made in the body another call supplies leaves the owning call cancellable`() {
    val slot = SempodsCallSlot()
    val nestedReturned = CountDownLatch(1)
    val body = SempodsBody.stream(null) {
      assertEquals(204, transport.send(get("/quick")).statusCode)
      nestedReturned.countDown()
      "payload".byteInputStream()
    }

    val ended = inSlot(slot) { transport.send(transport.newRequest(uri("/slow")).POST(body).build()) }
    assertTrue(nestedReturned.await(5, TimeUnit.SECONDS))
    Thread.sleep(200)
    slot.cancel()

    val failure = ended.get(5, TimeUnit.SECONDS)
    assertTrue(failure is IOException, "the owning call ended with $failure")
  }

  @Test
  fun `a cancel while a call made in another call's read waits ends both`() {
    val slot = SempodsCallSlot()
    val nesting = CountDownLatch(1)
    val nested = CompletableFuture<Throwable?>()

    val ended = inSlot(slot) {
      transport.sendStreaming(get("/trickle")) { owning ->
        owning.bodyStream().read()
        nesting.countDown()
        nested.complete(runCatching { transport.send(get("/slow")) }.exceptionOrNull())
        owning.bodyStream().readAllBytes()
      }
    }
    assertTrue(nesting.await(5, TimeUnit.SECONDS))
    Thread.sleep(200)
    slot.cancel()

    assertTrue(nested.get(5, TimeUnit.SECONDS) is IOException, "the nested call ended with ${nested.get()}")
    val failure = ended.get(5, TimeUnit.SECONDS)
    assertTrue(failure is IOException, "the owning call ended with $failure")
  }
}
