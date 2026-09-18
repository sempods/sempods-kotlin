package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsContextCreate
import org.sempods.client.core.SempodsDecodingException
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What the registry group asks the pod for, and what it makes of the answers. */
class SempodsRdf4jContextsContractTest : MockPodTest() {

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

  private val contexts get() = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)).contexts()

  private val tasks get() = "$origin/alice/_system/contexts/tasks"

  private val label = "http://www.w3.org/2000/01/rdf-schema#label"

  private fun answer(status: Int, body: String = "") {
    val response = response().withStatusCode(status).withHeader("ETag", "\"r1\"")
    if (body.isNotEmpty()) response.withBody(body)
    server.`when`(request()).respond(response)
  }

  @Test
  fun `every read asks for N-Quads, and each statement keeps its context`() {
    answer(200, "<$tasks> <$label> \"Tasks\" <$tasks> .\n")

    val catalogue = contexts.listModel()
    val description = contexts.getModel(tasks)

    assertEquals(listOf("/alice/_system/contexts", "/alice/_system/contexts/tasks"), sent.map { it.url.encodedPath })
    assertEquals(listOf("application/n-quads", "application/n-quads"), sent.map { it.headers["Accept"] })
    listOf(catalogue, description).forEach { answer ->
      assertEquals("\"r1\"", answer.headers["ETag"])
      val statement = assertNotNull(answer.body).single()
      assertEquals(iri(tasks), statement.context)
      assertEquals(literal("Tasks"), statement.`object`)
    }
  }

  @Test
  fun `an entity tag is sent as given, and an unchanged answer is one without a model`() {
    answer(304)

    val unchanged = contexts.getModel(tasks, "\"r1\"")

    assertEquals("\"r1\"", sent.single().headers["If-None-Match"])
    assertEquals(304, unchanged.status)
    assertNull(unchanged.body)
  }

  @Test
  fun `a creation sends its fields as JSON and reads the description it is answered with`() {
    answer(201, "<$tasks> <$label> \"Tasks\" <$tasks> .\n")

    val created = contexts.create(tasks, SempodsContextCreate.fields().withLabel("Tasks"))

    val write = sent.single()
    assertEquals("PUT", write.method)
    assertEquals("application/json", write.headers["Content-Type"]?.substringBefore(';'))
    assertEquals("application/n-quads", write.headers["Accept"])
    assertEquals(201, created.status)
    assertEquals(literal("Tasks"), assertNotNull(created.body).single().`object`)
  }

  @Test
  fun `the catalogue's members are what it names with sd namedGraph`() {
    val notes = "$origin/alice/_system/contexts/notes"
    val catalogue = "$origin/alice/_system/contexts"
    val named = "http://www.w3.org/ns/sparql-service-description#namedGraph"
    answer(
      200,
      "<$catalogue> <$named> <$tasks> <$catalogue> .\n" +
        "<$catalogue> <$named> <$notes> <$catalogue> .\n" +
        // Repeated by a pod that describes the same member twice, and a statement that is not the
        // membership relation: neither belongs in the answer.
        "<$catalogue> <$named> <$tasks> <$catalogue> .\n" +
        "<$tasks> <$label> \"Tasks\" <$catalogue> .\n",
    )

    val iris = contexts.listIris()

    assertEquals(listOf(iri(tasks), iri(notes)), assertNotNull(iris.body), "in the pod's order, without repeats")
    assertEquals("\"r1\"", iris.headers["ETag"])
  }

  @Test
  fun `a pod the server does not know answers no catalogue at all`() {
    answer(404)

    assertNull(contexts.listIris().body)
  }

  @Test
  fun `a body that is not N-Quads is a decoding failure with the answer's status and headers`() {
    answer(200, """{"@id": "$tasks"}""")

    val refused = assertThrows<SempodsDecodingException> { contexts.listModel() }

    assertEquals(200, refused.status)
    assertEquals("\"r1\"", refused.headers["ETag"])
  }
}
