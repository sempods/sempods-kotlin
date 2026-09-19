package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsAdmission
import org.sempods.client.SempodsClientException
import org.sempods.client.SempodsCredentialSupplier
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsRequestAuth
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsWriteOptions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A model call is the core's call: the same credential, resend and admission as a raw call on the same pod. */
class SempodsRdf4jSessionTest : MockPodTest() {

  /** One call at a time and no queue, so a held call shows who shares the slot. */
  private val client = sempodsClient(SempodsAdmission(maxActive = 1, maxWaiting = 0))

  @AfterAll
  fun stopClient() {
    client.shutDown()
  }

  private val event get() = "$origin/alice/events/1"

  private val tasks = "https://pods.example/alice/_system/contexts/tasks"

  private fun pod(auth: SempodsRequestAuth = SempodsRequestAuth.anonymous()) =
    SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice"), auth), client)

  private fun minting(minted: AtomicInteger) =
    SempodsRequestAuth.refreshable(SempodsCredentialSupplier { _, _ -> "token-${minted.incrementAndGet()}" })

  @Test
  fun `a raw read and a model read share one credential, renewed once for both`() {
    server.`when`(request().withHeader("Authorization", "Bearer token-1")).respond(response().withStatusCode(401))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("<urn:s> <urn:p> \"o\" .\n"))
    val pod = pod(minting(AtomicInteger()))

    assertEquals(200, pod.resources().getText(event).status)
    assertEquals(1, SempodsRdf4jPod(pod).resources().getModel(event).body?.size)

    val sent = server.retrieveRecordedRequests(request()).map { it.getFirstHeader("Authorization") }
    assertEquals(listOf("Bearer token-1", "Bearer token-2", "Bearer token-2"), sent)
  }

  @Test
  fun `a model write refused for its credential is sent again with the same body`() {
    server.`when`(request().withHeader("Authorization", "Bearer token-1")).respond(response().withStatusCode(401))
    server.`when`(request()).respond(response().withStatusCode(204))
    val model = LinkedHashModel().apply { add(iri(event), iri("https://schema.org/name"), literal("One")) }

    val written = SempodsRdf4jPod(pod(minting(AtomicInteger()))).resources().put(event, model, SempodsWriteOptions.inContext(tasks))

    assertEquals(204, written.status)
    val attempts = server.retrieveRecordedRequests(request().withMethod("PUT"))
    assertEquals(2, attempts.size)
    assertContentEquals(attempts[0].bodyAsRawBytes, attempts[1].bodyAsRawBytes)
  }

  @Test
  fun `a model call waits for the slot a raw call holds`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("<urn:s> <urn:p> \"o\" .\n"))
    val pod = pod()
    val rdf = SempodsRdf4jPod(pod)

    val held = pod.calls.newCall(pod.session.newRequest("GET", "events/1").build()).execute()
    held.use {
      val refused = assertThrows<SempodsClientException> { rdf.resources().getModel(event) }
      assertTrue(refused.message.orEmpty().startsWith("Refused"), refused.message)
    }

    assertEquals(1, rdf.resources().getModel(event).body?.size)
  }
}
