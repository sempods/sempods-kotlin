package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.rio.RDFFormat
import org.eclipse.rdf4j.rio.Rio
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsContextSelection
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsReadOptions
import org.sempods.client.core.SempodsResponse
import org.sempods.client.core.SempodsSession
import org.sempods.client.core.SempodsWriteOptions
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the resource and subject groups put on the wire, and what they make of the answer.
 *
 * The two groups differ in the address alone, so every case runs over both.
 */
class SempodsRdf4jResourcesContractTest : MockPodTest() {

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

  private val event get() = "$origin/alice/events/1"

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private val notes = "https://pods.example/alice/_system/contexts/notes"

  /** One group's model operations, and the path it sends for [event]. */
  private inner class Group(name: String) {
    private val rdf = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client))

    val path = if (name == RESOURCES) {
      "/alice/events/1"
    } else {
      "/alice/_system/resources/" + Base64.getUrlEncoder().withoutPadding().encodeToString(event.toByteArray())
    }

    val getModel: (String, SempodsReadOptions) -> SempodsResponse<Model> =
      if (name == RESOURCES) rdf.resources()::getModel else rdf.subjects()::getModel

    val put: (String, Model, SempodsWriteOptions) -> SempodsResponse<ByteArray> =
      if (name == RESOURCES) rdf.resources()::put else rdf.subjects()::put
  }

  private fun answer(status: Int, body: String = "", vararg headers: Pair<String, String>) {
    val response = response().withStatusCode(status)
    if (body.isNotEmpty()) response.withBody(body)
    headers.forEach { (name, value) -> response.withHeader(name, value) }
    server.`when`(request()).respond(response)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a read asks for N-Quads in the selected contexts, and never for grouped JSON-LD`(name: String) {
    answer(200, "")
    val group = Group(name)

    group.getModel(event, SempodsReadOptions.of(SempodsContextSelection.of(tasks, notes)).withIncludeContexts(true))

    val read = sent.single()
    assertEquals("GET", read.method)
    assertEquals(group.path, read.url.encodedPath)
    assertEquals("application/n-quads", read.headers["Accept"])
    assertEquals(listOf(tasks, notes), read.url.queryParameterValues("context"))
    assertNull(read.url.queryParameter("include_contexts"))
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `every statement keeps its context, the same triple in two contexts included`(name: String) {
    answer(
      200,
      """
      <$event> <https://schema.org/name> "One" <$tasks> .
      <$event> <https://schema.org/name> "One" <$notes> .
      <$event> <https://schema.org/about> <https://pods.example/alice/topics/rdf> <$notes> .
      """.trimIndent(),
      "ETag" to "\"v1\"",
    )

    val read = Group(name).getModel(event, SempodsReadOptions.defaults())

    assertEquals(200, read.status)
    assertEquals("\"v1\"", read.headers["ETag"])
    val model = assertNotNull(read.body)
    assertEquals(3, model.size)
    assertEquals(setOf(iri(tasks), iri(notes)), model.contexts())
    assertEquals(1, model.filter(null, null, null, iri(tasks)).size)
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `an answer without a body is one without a model`(name: String) {
    val group = Group(name)

    answer(404)
    assertNull(group.getModel(event, SempodsReadOptions.defaults()).body)

    server.reset()
    answer(304, "", "ETag" to "\"v1\"")
    val unchanged = group.getModel(event, SempodsReadOptions.defaults().withIfNoneMatch("\"v1\""))
    assertEquals(304, unchanged.status)
    assertNull(unchanged.body)

    sent.clear()
    val none = group.getModel(event, SempodsReadOptions.of(SempodsContextSelection.none()))
    assertEquals(404, none.status)
    assertNull(none.body)
    assertTrue(sent.isEmpty(), "none() is answered without a request")
  }

  @ParameterizedTest
  @ValueSource(strings = [RESOURCES, SUBJECTS])
  fun `a write sends the model as JSON-LD, each statement in its context, with the options as given`(name: String) {
    answer(204)
    val group = Group(name)
    val model = LinkedHashModel().apply {
      add(iri(event), iri("https://schema.org/name"), literal("One"), iri(tasks))
      add(iri(event), iri("https://schema.org/description"), literal("Eins", "de-CH"))
      // Sent as it is: what a context other than the target means is the pod's to decide.
      add(iri(event), iri("https://schema.org/about"), literal("elsewhere"), iri(notes))
    }

    val written = group.put(event, model, SempodsWriteOptions.inContext(tasks).withIfMatch("\"v1\""))

    assertEquals(204, written.status)
    val write = sent.single()
    assertEquals("PUT", write.method)
    assertEquals(group.path, write.url.encodedPath)
    assertEquals("application/ld+json", write.headers["Content-Type"]?.substringBefore(';'))
    assertEquals(listOf(tasks), write.url.queryParameterValues("context"))
    assertEquals("\"v1\"", write.headers["If-Match"])
    val jsonLd = assertNotNull(write.body)
    assertTrue(Models.isomorphic(model, Rio.parse(ByteArrayInputStream(jsonLd), RDFFormat.JSONLD)), "sent: ${String(jsonLd)}")
  }

  private companion object {
    const val RESOURCES = "resources"
    const val SUBJECTS = "subjects"
  }
}
