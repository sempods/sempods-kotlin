package org.sempods.api.pod.system.find

import com.google.inject.Inject
import org.eclipse.rdf4j.model.util.Values.iri
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsFindQuery
import org.sempods.client.SempodsGraphFormat
import org.sempods.commons.tests.TestUtil
import org.sempods.pods.contexts.persist.PodContextsDao
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The happy paths of `_system/find` through the published client's `pod.find()`: what a consumer
 * reads from the answer. Refusals and exact headers stay raw in [FindEndpointHttpTest].
 */
class FindClientHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  @Test
  fun `a find through the client answers the hits as JSON-LD text`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val needle = "clientneedle${TestUtil.randomId()}"
    val event = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = event, context = sempodsTestFactory.publicContextUri(pod.name), name = "$needle party")

    val found = podAs(pod.name).find().text(SempodsFindQuery.text(needle).withTypes("https://schema.org/Event"))

    assertEquals(200, found.status)
    assertTrue(found.body.orEmpty().contains(event.toString()), found.body)
  }

  @Test
  fun `a selection narrows the find, none matches nothing, and the grouped model keeps each context`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val needle = "clientctx${TestUtil.randomId()}"
    val (contextA, contextB) = listOf("apps/test-app/a", "apps/test-app/b").map { path ->
      sempodsUriBuilder.buildContext(pod.name, path).also {
        podContextsDao.create(podId = checkNotNull(pod.id), contextUri = it.toString(), label = null, description = null, createdBy = "test")
      }
    }
    val eventA = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    val eventB = sempodsTestFactory.eventUri(podName = pod.name, eventId = TestUtil.randomId())
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = eventA, context = contextA, name = "$needle in A")
    sempodsTestFactory.seedEvent(pod = pod.name, eventUri = eventB, context = contextB, name = "$needle in B")
    val token = mintScopedToken(pod.name, listOf("$contextA#read", "$contextB#read"))
    val find = podAs(pod.name, bearer = token).find()

    val onlyA = find.text(SempodsFindQuery.text(needle).withSelection(SempodsContextSelection.of(contextA.toString())), SempodsGraphFormat.N_QUADS)
    assertTrue(onlyA.body.orEmpty().contains(eventA.toString()), onlyA.body)
    assertFalse(onlyA.body.orEmpty().contains(eventB.toString()), onlyA.body)

    val none = find.text(SempodsFindQuery.text(needle).withSelection(SempodsContextSelection.none()), SempodsGraphFormat.N_QUADS)
    assertEquals(200, none.status)
    assertEquals("", none.body.orEmpty().trim())

    val grouped = assertNotNull(rdfAs(pod.name, bearer = token).find().model(SempodsFindQuery.text(needle).withIncludeContexts(true)).body)
    assertEquals(setOf(iri(contextA.toString())), grouped.filter(iri(eventA.toString()), null, null).contexts())
    assertEquals(setOf(iri(contextB.toString())), grouped.filter(iri(eventB.toString()), null, null).contexts())
  }
}
