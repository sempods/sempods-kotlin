package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.rio.RDFHandlerException
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler
import org.eclipse.rdf4j.rio.helpers.StatementCollector
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsSession
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** A context export and a graph query read while they arrive: what a handler receives, and how a failure is reported. */
class SempodsRdf4jStreamsContractTest : MockPodTest() {

  private val sent = CopyOnWriteArrayList<Sent>()

  private val client = recordingClient(sent)

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  @BeforeEach
  fun forgetRequests() {
    sent.clear()
  }

  private val rdf get() = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client))

  private val tasks get() = "$origin/alice/_system/contexts/tasks"

  private fun answer(body: String) {
    server.`when`(request()).respond(response().withStatusCode(200).withHeader("X-Trace", "t-1").withBody(body))
  }

  @Test
  fun `an export sets the exported context on every statement, into a handler or a model`() {
    answer("<urn:s> <urn:p> \"one\" .\n<urn:s> <urn:q> <urn:o> .\n")
    val handled = LinkedHashModel()

    val count = rdf.contexts().export(tasks, StatementCollector(handled))
    val model = rdf.contexts().exportModel(tasks)

    assertEquals(2L, count.body)
    assertEquals("application/n-quads", sent.first().headers["Accept"])
    listOf(handled, assertNotNull(model.body)).forEach {
      assertEquals(2, it.size)
      assertEquals(setOf(iri(tasks)), it.contexts())
    }
  }

  @Test
  fun `a graph stream asks for N-Quads in the selection, and its statements carry no context`() {
    answer("<urn:s> <urn:p> \"one\" .\n")
    val handled = LinkedHashModel()

    val count = rdf.sparql().graphStream("CONSTRUCT WHERE { ?s ?p ?o }", StatementCollector(handled), SempodsContextSelection.of(tasks))

    assertEquals(1L, count.body)
    val query = sent.single()
    assertEquals("application/n-quads", query.headers["Accept"])
    assertEquals(listOf(tasks), query.url.queryParameterValues("default-graph-uri"))
    assertEquals(setOf(null), handled.contexts())
  }

  @Test
  fun `a body that stops parsing is a decoding failure, after the statements before it were handed on`() {
    answer("<urn:s> <urn:p> \"one\" .\n<urn:s> <urn:p> .\n")
    val handled = LinkedHashModel()

    val refused = assertThrows<SempodsDecodingException> {
      rdf.sparql().graphStream("CONSTRUCT WHERE { ?s ?p ?o }", StatementCollector(handled))
    }

    assertEquals(200, refused.status)
    assertEquals("t-1", refused.headers["X-Trace"])
    assertEquals(1, handled.size)
  }

  @Test
  fun `what the handler throws reaches the caller as it is`() {
    answer("<urn:s> <urn:p> \"one\" .\n<urn:s> <urn:p> \"two\" .\n")
    val own = RDFHandlerException("the caller's own")
    val handler = object : AbstractRDFHandler() {
      override fun handleStatement(st: Statement) = throw own
    }

    assertSame(own, assertThrows<RDFHandlerException> { rdf.contexts().export(tasks, handler) })
  }

  @Test
  fun `a connection lost in the body reaches the caller as the connection's IOException`() {
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
      thread(isDaemon = true) {
        socket.accept().use { connection ->
          val input = BufferedInputStream(connection.getInputStream())
          val head = StringBuilder()
          while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
          val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
          repeat(length) { input.read() }
          val part = "<urn:s> <urn:p> \"one\" .\n"
          connection.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n$part".toByteArray())
          connection.getOutputStream().flush()
        }
      }
      val pod = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("http://127.0.0.1:${socket.localPort}/alice")), client))
      val handled = LinkedHashModel()

      val lost = assertThrows<IOException> { pod.sparql().graphStream("CONSTRUCT WHERE { ?s ?p ?o }", StatementCollector(handled)) }

      assertFalse(lost is SempodsDecodingException, "$lost")
      assertEquals(1, handled.size)
    }
  }
}
