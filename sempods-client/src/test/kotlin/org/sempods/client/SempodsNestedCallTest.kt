package org.sempods.client

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * A call an operation makes while another of its calls is still in flight — in the body that call
 * supplies, or in the block reading its answer.
 *
 * **The case a cancel handle per thread got wrong.** Both calls run on the operation's one thread,
 * one inside the other, and a handle that holds the call the thread is on replaces the owning one
 * when the nested call starts and clears it when that call returns: a cancel afterwards then
 * reaches nothing and the owning call runs to its deadline. `SempodsAsyncOperation` holds the calls
 * rather than the thread, so nesting is not a case it has to know about — these tests are what says
 * so. Remove the tracking in `SempodsAsyncOperation.track` and both fail.
 */
class SempodsNestedCallTest : MockPodTest() {

  /**
   * Deadlines far longer than these tests wait, so that only the cancel can end a call.
   *
   * With OkHttp's ten-second read timeout the owning call dies on its own while the assertion is
   * still waiting, and a test that only looks at the exception type cannot tell that from a cancel
   * reaching it. Every assertion below is therefore on the clock as well.
   */
  private val client = sempodsClient(admission = null) {
    readTimeout(Duration.ofSeconds(60))
    writeTimeout(Duration.ofSeconds(60))
    callTimeout(Duration.ofSeconds(60))
  }

  /** Long enough for a cancel to land, far short of anything a deadline could explain. */
  private val withinCancel = 5L

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun session() = SempodsSession(SempodsPodBase.of("$origin/alice"))

  /** The work's outcome, unwrapped from the stage — or nothing, when it is still running. */
  private fun <T> SempodsAsyncOperation<T>.outcomeWithin(seconds: Long): Result<T>? =
    try {
      Result.success(result().toCompletableFuture().get(seconds, TimeUnit.SECONDS))
    } catch (failed: ExecutionException) {
      Result.failure(checkNotNull(failed.cause))
    } catch (_: TimeoutException) {
      null
    }

  @Test
  fun `a call made in the body another call supplies leaves the owning call cancellable`() {
    server.`when`(request().withPath("/alice/quick")).respond(response().withStatusCode(204))
    server.`when`(request().withPath("/alice/slow"))
      .respond(response().withStatusCode(204).withDelay(TimeUnit.SECONDS, 30))
    val nestedReturned = CountDownLatch(1)

    val operation = SempodsAsync(client).submit { calls ->
      val body = object : RequestBody() {
        override fun contentType() = "text/plain".toMediaType()

        override fun writeTo(sink: BufferedSink) {
          calls.newCall(session().newRequest("GET", "quick").build()).execute().close()
          nestedReturned.countDown()
          sink.writeUtf8("payload")
        }
      }
      calls.newCall(session().newRequest("POST", "slow").post(body).build()).execute().close()
    }
    assertTrue(nestedReturned.await(5, TimeUnit.SECONDS), "the nested call never ran")
    // The nested call has returned; the owning one is waiting for its answer.
    Thread.sleep(200)

    operation.cancel()

    val outcome = operation.outcomeWithin(withinCancel)
    assertTrue(outcome?.exceptionOrNull() is CancellationException, "the owning call outlived the cancel: $outcome")
  }

  @Test
  fun `a cancel while a call made in another call's read waits ends both`() {
    server.`when`(request().withPath("/alice/slow"))
      .respond(response().withStatusCode(204).withDelay(TimeUnit.SECONDS, 30))
    // A body that keeps arriving, a byte at a time, so the read is still open when the cancel lands.
    val trickle = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      trickle.accept().use { connection ->
        val input = connection.getInputStream().bufferedReader()
        while (input.readLine()?.isNotEmpty() == true) Unit
        val output = connection.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
        try {
          while (true) {
            output.write(ByteArray(1))
            output.flush()
            Thread.sleep(20)
          }
        } catch (_: IOException) {
          // The client cancelled.
        }
      }
    }
    val nesting = CountDownLatch(1)
    val nested = CompletableFuture<Throwable?>()

    try {
      val operation = SempodsAsync(client).submit { calls ->
        SempodsForeignTarget(calls).getStream("http://127.0.0.1:${trickle.localPort}/trickle", "*/*", { body ->
          body.read()
          nesting.countDown()
          nested.complete(
            runCatching { calls.newCall(session().newRequest("GET", "slow").build()).execute().close() }.exceptionOrNull(),
          )
          body.readAllBytes()
          "read to the end"
        })
      }
      assertTrue(nesting.await(5, TimeUnit.SECONDS), "the read never reached the nested call")
      Thread.sleep(200)

      operation.cancel()

      assertTrue(nested.get(withinCancel, TimeUnit.SECONDS) is IOException, "the nested call outlived the cancel")
      val outcome = operation.outcomeWithin(withinCancel)
      assertTrue(outcome?.exceptionOrNull() is CancellationException, "the owning call outlived the cancel: $outcome")
    } finally {
      trickle.close()
    }
  }
}
