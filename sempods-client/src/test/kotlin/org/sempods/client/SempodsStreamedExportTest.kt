package org.sempods.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

/**
 * What a streamed read does while the pod is still sending.
 *
 * The pod here is a socket writing HTTP by hand, because these cases need a body that leaves in
 * pieces, at a moment the test decides — which is what every buffered server layer takes away.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsStreamedExportTest {

  private lateinit var pod: ServerSocket

  private val client = sempodsClient()

  /** What the pod sends first, and what it sends once [release] is counted down. */
  private val first = "<urn:s> <urn:p> <urn:first> .\n".toByteArray()

  private val rest = "<urn:s> <urn:p> <urn:rest> .\n".toByteArray()

  /** Read by the pod's thread and written by the test's, so the pod cannot await a stale latch. */
  @Volatile
  private lateinit var release: CountDownLatch

  /** What the pod has done so far: the test reads it while the pod is still in the middle. */
  private val steps = CopyOnWriteArrayList<String>()

  /** Set for the case where the pod stops inside a body whose length it declared. */
  @Volatile
  private var breakOff = false

  @BeforeAll
  fun startPod() {
    pod = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    Thread {
      while (!pod.isClosed) {
        val connection = try {
          pod.accept()
        } catch (_: IOException) {
          return@Thread
        }
        Thread { serve(connection) }.apply { isDaemon = true }.start()
      }
    }.apply { isDaemon = true }.start()
  }

  @AfterAll
  fun stopPod() {
    pod.close()
    client.shutDown()
  }

  @BeforeEach
  fun forget() {
    steps.clear()
    release = CountDownLatch(1)
  }

  /** One request, answered in pieces. `Connection: close`, so no test meets another's connection. */
  private fun serve(connection: Socket) {
    connection.use {
      // The pieces are the point, so nothing may be held back waiting for the next one.
      connection.tcpNoDelay = true
      val input = connection.getInputStream()
      val head = StringBuilder()
      while (!head.endsWith("\r\n\r\n")) {
        val byte = input.read()
        if (byte < 0) return
        head.append(byte.toChar())
      }
      input.readNBytes(Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0)
      steps += head.lineSequence().first().trim()

      val out = connection.getOutputStream()
      if (breakOff) {
        // A length the pod then does not deliver: the client meets the end of the connection inside
        // the body, which is the failure an export has to survive without losing what arrived.
        out.write(header("Content-Length: ${first.size + rest.size}"))
        out.write(first)
        out.flush()
        steps += "wrote first"
        return
      }
      out.write(header("Transfer-Encoding: chunked"))
      writeChunk(out, first)
      steps += "wrote first"
      val released = release.await(5, TimeUnit.SECONDS)
      try {
        if (released) {
          writeChunk(out, rest)
          steps += "wrote rest"
        }
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
      } catch (_: IOException) {
        steps += "the client had gone"
      }
    }
  }

  private fun header(framing: String) =
    "HTTP/1.1 200 OK\r\nContent-Type: application/n-quads\r\nConnection: close\r\n$framing\r\n\r\n".toByteArray()

  /** One chunk in one write, so the pod is never the reason a reader waits. */
  private fun writeChunk(out: OutputStream, bytes: ByteArray) {
    out.write("${bytes.size.toString(16)}\r\n".toByteArray() + bytes + "\r\n".toByteArray())
    out.flush()
  }

  /**
   * Exactly [count] bytes, asked for in reads that are never empty.
   *
   * `readNBytes(count)` would do — until [count] is all there is: it asks once more, for nothing, and
   * an okio stream answers an empty read by waiting for bytes that only the end of the body brings.
   */
  private fun InputStream.exactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var read = 0
    while (read < count) {
      val more = read(bytes, read, count - read)
      check(more > 0) { "the body ended after $read of $count bytes" }
      read += more
    }
    return bytes
  }

  private fun contexts(): SempodsPodContexts =
    SempodsPod(SempodsSession(SempodsPodBase.of("http://127.0.0.1:${pod.localPort}/alice")), client).contexts()

  private val tasks get() = "http://127.0.0.1:${pod.localPort}/alice/_system/contexts/tasks"

  /** An output stream the caller owns, which records what was written and whether it was closed. */
  private class Owned : OutputStream() {
    val written = ByteArrayOutputStream()
    val closed = AtomicBoolean()

    override fun write(byte: Int) = written.write(byte)

    override fun write(bytes: ByteArray, offset: Int, length: Int) = written.write(bytes, offset, length)

    override fun close() {
      closed.set(true)
    }
  }

  @Test
  fun `a reader sees the first statements before the pod has sent the last`() {
    val exported = contexts().export(
      tasks,
      { body ->
        // Blocks until the pod's first piece arrives; the pod writes the rest only once this says so.
        val seen = body.exactly(first.size)
        release.countDown()
        seen + body.readBytes()
      },
    )

    assertEquals(200, exported.status)
    assertContentEquals(first + rest, exported.body, "the pod did $steps")
    assertEquals("POST /alice/_system/sparql/query HTTP/1.1", steps.first())
  }

  @Test
  fun `a reader that stops early ends the transfer`() {
    val exported = contexts().export(tasks, { body -> body.exactly(first.size) })

    assertContentEquals(first, exported.body)
    // The call is over while the pod still holds the rest, waiting for a signal that never comes.
    assertFalse("wrote rest" in steps, "the pod did $steps")
    release.countDown()
  }

  @Test
  fun `the caller's stream is written to and never closed, however the export ends`() {
    val whole = Owned()
    release.countDown()

    val written = contexts().exportTo(tasks, whole)

    assertEquals((first.size + rest.size).toLong(), written.body)
    assertContentEquals(first + rest, whole.written.toByteArray())
    assertFalse(whole.closed.get(), "the stream is the caller's to close")

    breakOff = true
    try {
      val broken = Owned()
      assertThrows<IOException> { contexts().exportTo(tasks, broken) }
      assertContentEquals(first, broken.written.toByteArray(), "what arrived before the break is written")
      assertFalse(broken.closed.get(), "a failed export closes the caller's stream no more than a whole one")
      assertTrue("wrote first" in steps, "the pod did $steps")
    } finally {
      breakOff = false
    }
  }
}
