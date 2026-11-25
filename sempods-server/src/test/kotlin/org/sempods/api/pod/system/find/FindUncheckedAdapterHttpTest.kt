package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values.bnode
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.net.SempodsVocabulary
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.tests.TestUtil
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.toIri
import java.io.ByteArrayInputStream
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `find` with an engine that does not scope itself: an index answering whatever it holds, here for
 * an anonymous caller who reads the pod's public context alone.
 */
class FindUncheckedAdapterHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val findResult = iri(SempodsVocabulary.FIND_RESULT)
  private val text = iri("https://schema.org/text")
  private val name = iri("https://schema.org/name")

  /** A pod with an event in its public context and one in a private context, and what an index holds of both. */
  private inner class Indexed {
    val pod = sempodsTestFactory.newPod()
    val publicContext = sempodsTestFactory.publicContextUri(pod.name).toIri()
    val privateContext = sempodsUriBuilder.buildContext(pod.name, "apps/test-app/default").also {
      podContextsDao.create(podId = checkNotNull(pod.id), contextUri = it.toString(), label = null, description = null, createdBy = "test")
    }.toIri()
    val publicEvent = seeded(publicContext)
    val privateEvent = seeded(privateContext)

    /**
     * An observation for each event where the index matched it, one for the public event in the
     * private context, and what the index remembers of the private event.
     */
    val model = LinkedHashModel().apply {
      listOf(publicEvent to publicContext, privateEvent to privateContext, publicEvent to privateContext).forEach { (event, context) ->
        val observation = bnode()
        add(event, findResult, observation, context)
        add(observation, text, literal("…seen by the index…"), context)
      }
      add(privateEvent, name, literal("private name"), privateContext)
      add(privateEvent, name, literal("private name, filed as public"), publicContext)
      add(publicEvent, name, literal("a private fact of the public event, filed as public"), publicContext)
    }

    private fun seeded(context: IRI): IRI {
      val event = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
      sempodsTestFactory.seedEvent(pod = pod.name, eventUri = event, context = URI(context.stringValue()), name = "seeded")
      return event.toIri()
    }

    fun find(vararg query: Pair<String, String>): Model {
      val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/find")
        .addQueryParam("text", "unmatched${TestUtil.randomId()}")
        .also { request -> query.forEach { (key, value) -> request.addQueryParam(key, value) } }
        .addQueryParam("include_contexts", "true")
        .addHeader("Accept", "application/n-quads")
        .execute()
      assertEquals(200, response.statusCode, response.responseBody)
      return ByteArrayInputStream(response.responseBodyAsBytes).use { RdfWriterUtil.readNQuads(it) }
    }
  }

  @Test
  fun `neither a hit the caller cannot read nor anything the index said about it reaches the answer`() = withSetup {
    val indexed = Indexed()
    findAdapter.overReturnWith(indexed.model)

    val answer = indexed.find()

    assertEquals(setOf(indexed.publicEvent), answer.subjects().filterIsInstance<IRI>().toSet())
    assertEquals(1, answer.filter(null, findResult, null).size, "only the readable hit's observation: $answer")
    assertTrue(answer.none { it.subject == indexed.privateEvent || it.`object` == indexed.privateEvent }, "nothing about the private event: $answer")
    assertEquals(setOf(indexed.publicContext), answer.contexts().toSet())
    assertEquals(setOf(literal("seeded")), answer.filter(indexed.publicEvent, name, null).objects(), "the store's name, not the index's: $answer")
  }

  @Test
  fun `a hit whose stored type is not a requested one is dropped`() = withSetup {
    val indexed = Indexed()
    findAdapter.overReturnWith(indexed.model)

    assertEquals(0, indexed.find("type" to "https://schema.org/Person").size)
    assertEquals(setOf(indexed.publicEvent), indexed.find("type" to "https://schema.org/Event").subjects().filterIsInstance<IRI>().toSet())
  }
}
