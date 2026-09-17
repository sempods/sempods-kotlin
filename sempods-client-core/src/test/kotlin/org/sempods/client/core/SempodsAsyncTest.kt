package org.sempods.client.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.Call
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/** Work run away from the caller's thread: what it completes with, and what its cancel reaches. */
class SempodsAsyncTest : MockPodTest() {

  private val client = sempodsClient()

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private fun session(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsSession(SempodsPodBase.of("$origin/alice"), auth)

  private fun get(calls: Call.Factory, path: String = "x") =
    calls.newCall(session().newRequest("GET", path).build()).execute()

  /** The work's outcome: its value, or what it failed with, unwrapped from the stage. */
  private fun <T> SempodsAsyncOperation<T>.outcome(): Result<T> =
    try {
      Result.success(result().toCompletableFuture().get(10, TimeUnit.SECONDS))
    } catch (failed: ExecutionException) {
      Result.failure(checkNotNull(failed.cause))
    }

  private fun millisSince(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

  @Test
  fun `a group call returns its value, and a dependent stage runs on the operation's virtual thread`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("""{"dateModified":null}"""))
    val release = CountDownLatch(1)

    val operation = SempodsAsync(client).submit { calls ->
      release.await(5, TimeUnit.SECONDS)
      SempodsPod(session(), calls).metadata().dateModified()
    }
    val onVirtualThread = operation.result().thenApply { Thread.currentThread().isVirtual }.toCompletableFuture()
    release.countDown()

    assertEquals(200, operation.outcome().getOrThrow().status)
    assertTrue(onVirtualThread.get(5, TimeUnit.SECONDS))
    assertFalse(operation.isCancelled)
  }

  @Test
  fun `a failure of the work is how the operation completes`() {
    server.`when`(request()).respond(response().withStatusCode(500))

    val operation = SempodsAsync(client).submit { calls -> SempodsPod(session(), calls).metadata().dateModified() }

    val failure = operation.outcome().exceptionOrNull()
    assertTrue(failure is SempodsStatusException, "was $failure")
    assertEquals(500, failure.status)
  }

  @Test
  fun `an operation cancelled before it starts completes at once and never runs`() {
    val queued = CopyOnWriteArrayList<Runnable>()
    val ran = AtomicBoolean()

    val operation = SempodsAsync(client, Executor { queued += it }).submit { calls ->
      ran.set(true)
      get(calls).close()
    }
    operation.cancel()

    // Complete although the executor has not run the task, and may never.
    assertTrue(operation.result().toCompletableFuture().isDone)
    assertTrue(operation.outcome().exceptionOrNull() is CancellationException)
    queued.single().run()
    assertFalse(ran.get())
    assertTrue(operation.isCancelled)
    assertTrue(server.retrieveRecordedRequests(request()).isEmpty())
  }

  @Test
  fun `a cancel reaches a call waiting for an admission slot, and the slot stays usable`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 1)).closing { narrow ->
      val holding = get(narrow)
      val operation = try {
        val waiting = SempodsAsync(narrow).submit { calls -> get(calls).use { it.code } }
        Thread.sleep(200)
        val started = System.nanoTime()

        waiting.cancel()

        assertTrue(waiting.outcome().exceptionOrNull() is CancellationException)
        assertTrue(millisSince(started) < 2_000, "the wait outlived the cancel by ${millisSince(started)}ms")
        waiting
      } finally {
        holding.close()
      }
      assertTrue(operation.isCancelled)
      assertEquals(1, server.retrieveRecordedRequests(request()).size, "the cancelled call was sent")
      get(narrow).use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun `a cancel reaches a call waiting for its answer, and the stage completes after the work returned`() {
    server.`when`(request()).respond(response().withStatusCode(200).withDelay(TimeUnit.SECONDS, 10))
    val returned = AtomicBoolean()

    val operation = SempodsAsync(client).submit { calls ->
      try {
        get(calls).use { it.code }
      } finally {
        returned.set(true)
      }
    }
    val returnedFirst = operation.result().handle { _, _ -> returned.get() }.toCompletableFuture()
    Thread.sleep(300)
    val started = System.nanoTime()

    operation.cancel()

    val failure = operation.outcome().exceptionOrNull()
    assertTrue(failure is CancellationException, "was $failure")
    assertTrue(failure.cause is IOException, "cause was ${failure.cause}")
    assertTrue(millisSince(started) < 2_000, "the answer's wait outlived the cancel by ${millisSince(started)}ms")
    assertTrue(returnedFirst.get(5, TimeUnit.SECONDS), "the stage completed before the work returned")
  }

  @Test
  fun `a cancel reaches a body being read, and the connection closes`() {
    val closed = CountDownLatch(1)
    val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      socket.accept().use { connection ->
        val input = connection.getInputStream().bufferedReader()
        while (input.readLine()?.isNotEmpty() == true) Unit
        val output = connection.getOutputStream()
        output.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray())
        try {
          while (true) {
            output.write(ByteArray(64))
            output.flush()
            Thread.sleep(20)
          }
        } catch (gone: IOException) {
          closed.countDown()
        }
      }
    }
    val reading = CountDownLatch(1)

    try {
      val operation = SempodsAsync(client).submit { calls ->
        SempodsForeignTarget(calls).getStream("http://127.0.0.1:${socket.localPort}/slow", "*/*", { body ->
          val buffer = ByteArray(64)
          while (body.read(buffer) >= 0) reading.countDown()
          "read to the end"
        })
      }
      assertTrue(reading.await(5, TimeUnit.SECONDS))

      operation.cancel()

      assertTrue(operation.outcome().exceptionOrNull() is CancellationException)
      assertTrue(closed.await(5, TimeUnit.SECONDS), "the server kept writing to an open connection")
    } finally {
      socket.close()
    }
  }

  @Test
  fun `a cancel between two calls keeps the second one from being sent`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    val first = CountDownLatch(1)
    val proceed = CountDownLatch(1)

    val operation = SempodsAsync(client).submit { calls ->
      get(calls, "first").close()
      first.countDown()
      proceed.await(5, TimeUnit.SECONDS)
      get(calls, "second").close()
    }
    assertTrue(first.await(5, TimeUnit.SECONDS))
    operation.cancel()
    proceed.countDown()

    assertTrue(operation.outcome().exceptionOrNull() is CancellationException)
    assertEquals(listOf("/alice/first"), server.retrieveRecordedRequests(request()).map { it.path.value })
  }

  @Test
  fun `cancelling one operation waiting for a shared credential leaves the one fetching it alone`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    val fetching = CountDownLatch(1)
    val release = CountDownLatch(1)
    val asked = AtomicInteger()
    val shared = SempodsRequestAuth.refreshable(
      SempodsCredentialSupplier { _, _ ->
        asked.incrementAndGet()
        fetching.countDown()
        release.await(10, TimeUnit.SECONDS)
        "t"
      },
    )
    val async = SempodsAsync(client)
    val call = { calls: Call.Factory -> calls.newCall(session(shared).newRequest("GET", "x").build()).execute().use { it.code } }

    try {
      val fetcher = async.submit(call)
      assertTrue(fetching.await(5, TimeUnit.SECONDS))
      val waiter = async.submit(call)
      Thread.sleep(200)
      val started = System.nanoTime()

      waiter.cancel()

      assertTrue(waiter.outcome().exceptionOrNull() is CancellationException)
      assertTrue(millisSince(started) < 2_000, "the credential wait outlived the cancel by ${millisSince(started)}ms")
      release.countDown()
      assertEquals(200, fetcher.outcome().getOrThrow())
      assertEquals(1, asked.get())
    } finally {
      release.countDown()
    }
  }

  @Test
  fun `a value the work returns after a cancel is closed, and a cancel after the return does nothing`() {
    val closed = AtomicInteger()
    val running = CountDownLatch(1)
    val release = CountDownLatch(1)
    val async = SempodsAsync(client)

    val cancelledFirst = async.submit { _ ->
      running.countDown()
      release.await(5, TimeUnit.SECONDS)
      AutoCloseable { closed.incrementAndGet() }
    }
    assertTrue(running.await(5, TimeUnit.SECONDS))
    cancelledFirst.cancel()
    release.countDown()
    assertTrue(cancelledFirst.outcome().exceptionOrNull() is CancellationException)
    assertEquals(1, closed.get())

    val returnedFirst = async.submit { _ -> AutoCloseable { closed.incrementAndGet() } }
    assertTrue(returnedFirst.outcome().isSuccess)
    returnedFirst.cancel()
    assertFalse(returnedFirst.isCancelled)
    assertEquals(1, closed.get(), "a delivered value was closed")
  }

  @Test
  fun `a cancel racing the work's return completes the stage once and leaks neither a slot nor a value`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    val returned = AtomicInteger()
    val closed = AtomicInteger()
    val delivered = AtomicInteger()

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0)).closing { narrow ->
      val async = SempodsAsync(narrow)
      repeat(200) { round ->
        // Every other round makes no call, so the cancel meets a work that is about to return.
        val operation = async.submit { calls ->
          if (round % 2 == 0) get(calls).close()
          returned.incrementAndGet()
          AutoCloseable { closed.incrementAndGet() }
        }
        operation.cancel()
        val outcome = operation.outcome()
        if (outcome.isSuccess) delivered.incrementAndGet() else assertTrue(outcome.exceptionOrNull() is CancellationException)
      }
      get(narrow).use { assertEquals(200, it.code) }
    }
    assertEquals(returned.get(), delivered.get() + closed.get(), "a value was neither delivered nor closed")
  }

  @Test
  fun `operations beyond the admission budget are refused at once`() {
    server.`when`(request()).respond(response().withStatusCode(200).withDelay(TimeUnit.MILLISECONDS, 500))

    sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 1)).closing { narrow ->
      val async = SempodsAsync(narrow)
      val operations = (1..5).map { async.submit { calls -> get(calls).use { it.code } } }
      val outcomes = operations.map { it.outcome() }

      assertEquals(2, outcomes.count { it.getOrNull() == 200 })
      assertEquals(3, outcomes.count { it.exceptionOrNull() is SempodsClientException })
    }
  }

  @Test
  fun `a caller's executor runs the work and is never shut down`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "caller-pool") }

    try {
      val operation = SempodsAsync(client, executor).submit { calls ->
        get(calls).close()
        Thread.currentThread().name
      }

      assertEquals("caller-pool", operation.outcome().getOrThrow())
      assertFalse(executor.isShutdown)
    } finally {
      executor.shutdownNow()
    }
  }
}
