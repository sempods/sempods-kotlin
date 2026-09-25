package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import org.eclipse.rdf4j.model.util.Values
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.utils.UriEncodingUtil
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import org.sempods.rdf.toIri
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A write that adds statements about a subject at or under the pod's `_system/contexts` answers `400`
 * and names the namespace, on every route that adds statements. The removals still reach what is stored
 * there. The rule is [org.sempods.pods.contexts.ContextPathRules.reservedSubjectReason].
 */
class ContextNamespaceWriteHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var podContextsDao: PodContextsDao

  private val api get() = SempodsModule.config.apiBaseUrl

  private val schemaName = "https://schema.org/name"
  private val schemaKnows = "https://schema.org/knows"

  private fun registerContext(pod: PodDbo, path: String): URI {
    val uri = sempodsUriBuilder.buildContext(pod.name, path)
    podContextsDao.create(
      podId = checkNotNull(pod.id),
      contextUri = uri.toString(),
      label = null,
      description = null,
      createdBy = "test",
    )
    return uri
  }

  private fun namespace(pod: PodDbo) = "$api${pod.name}/_system/contexts/"

  private fun b64(iri: String) = UriEncodingUtil.encodeUriToUrlSafeBase64(URI.create(iri))

  private fun inContext(url: String, context: URI) =
    "$url?context=${URLEncoder.encode(context.toString(), StandardCharsets.UTF_8)}"

  private fun resourceUrl(pod: PodDbo, iri: String, context: URI) = inContext("$api${pod.name}/_system/resources/${b64(iri)}", context)

  private fun slotUrl(pod: PodDbo, subject: String, predicate: String, context: URI) =
    inContext("$api${pod.name}/_system/resources/${b64(subject)}/${b64(predicate)}", context)

  private fun edgeUrl(pod: PodDbo, subject: String, predicate: String, target: String, context: URI) =
    inContext("$api${pod.name}/_system/resources/${b64(subject)}/${b64(predicate)}/${b64(target)}", context)

  private fun send(method: String, url: String, token: String, body: String? = null, contentType: String = "application/ld+json") =
    http.prepare(method, url)
      .addHeader("Authorization", "Bearer $token")
      .apply { body?.let { addHeader("Content-Type", contentType).setBody(it) } }
      .execute()

  private fun get(url: String, token: String) =
    http.prepareGet(url).addHeader("Accept", "application/ld+json").addHeader("Authorization", "Bearer $token").execute()

  /** The four writes that add statements about [subject]: resource `PUT` and `PATCH`, slot `PUT` and `POST`. */
  private fun additions(pod: PodDbo, subject: String, context: URI, token: String): Map<String, TestHttpResponse> = mapOf(
    "PUT resource" to send("PUT", resourceUrl(pod, subject, context), token, """{"@id":"$subject","$schemaName":"a note"}"""),
    "PATCH resource" to send(
      "PATCH", resourceUrl(pod, subject, context), token, """{"$schemaName":[{"@value":"a note"}]}""", "application/merge-patch+json",
    ),
    "PUT slot" to send("PUT", slotUrl(pod, subject, schemaName, context), token, """[{"@value":"a note"}]"""),
    "POST slot" to send("POST", slotUrl(pod, subject, schemaName, context), token, """{"@value":"a note"}"""),
  )

  private fun assertRefusedForTheNamespace(pod: PodDbo, label: String, response: TestHttpResponse) {
    assertEquals(400, response.statusCode, "$label: ${response.responseBody}")
    assertTrue(response.responseBody.contains("'${namespace(pod)}'"), "$label names the namespace: ${response.responseBody}")
  }

  @Test
  fun `every write that adds statements about a subject under the namespace answers 400 and names it`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))

    // The context IRI itself, the IRI a model derives from it, and one no context has.
    listOf(tasks.toString(), "$tasks/res-1", "${namespace(pod)}never/registered").forEach { subject ->
      additions(pod, subject, tasks, token).forEach { (label, response) ->
        assertRefusedForTheNamespace(pod, "$label <$subject>", response)
      }
      assertEquals(404, get(resourceUrl(pod, subject, tasks), token).statusCode, "nothing about <$subject> was stored")
    }
  }

  @Test
  fun `a write about the catalogue IRI answers 400 as well, and a segment beside it does not`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val catalogue = namespace(pod).removeSuffix("/")

    additions(pod, catalogue, tasks, token).forEach { (label, response) ->
      assertRefusedForTheNamespace(pod, "$label <$catalogue>", response)
    }
    assertEquals(404, get(resourceUrl(pod, catalogue, tasks), token).statusCode, "nothing about <$catalogue> was stored")

    val beside = "${catalogue}X"
    val put = send("PUT", resourceUrl(pod, beside, tasks), token, """{"@id":"$beside","$schemaName":"a note"}""")
    assertEquals(201, put.statusCode, put.responseBody)
  }

  @Test
  fun `a nested subject under the namespace is refused on both resource routes`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val note = "$api${pod.name}/notes/a"
    val body = """{"@id":"$note","https://schema.org/about":{"@id":"$tasks","$schemaName":"nested"}}"""

    assertRefusedForTheNamespace(pod, "LOD PUT", send("PUT", inContext(note, tasks), token, body))
    assertRefusedForTheNamespace(pod, "System PUT", send("PUT", resourceUrl(pod, note, tasks), token, body))

    // Pointing at a context IRI says nothing about it, so that stays an ordinary write.
    val pointing = send("PUT", inContext(note, tasks), token, """{"@id":"$note","https://schema.org/about":{"@id":"$tasks"}}""")
    assertEquals(201, pointing.statusCode, pointing.responseBody)
  }

  @Test
  fun `another spelling of the pod base reaches the namespace and is refused`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val upperCase = "${api.replace("http://localhost", "HTTP://LOCALHOST")}${pod.name}/_system/contexts/tasks/res-1"
    val encoded = "$api${pod.name}/%5Fsystem/contexts/tasks/res-1"

    listOf(upperCase, encoded).forEach { subject ->
      additions(pod, subject, tasks, token).forEach { (label, response) ->
        assertRefusedForTheNamespace(pod, "$label <$subject>", response)
      }
    }
  }

  @Test
  fun `a segment beside the namespace and another pod's namespace stay ordinary subjects`() {
    val pod = sempodsTestFactory.newPod()
    val other = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))

    listOf(
      "$api${pod.name}/_system;x/contexts/tasks",
      "$api${pod.name}/_system/contexts;x/tasks",
      "$api${other.name}/_system/contexts/tasks",
    ).forEach { subject ->
      additions(pod, subject, tasks, token).forEach { (label, response) ->
        assertTrue(response.statusCode in 200..204, "$label <$subject>: ${response.statusCode} ${response.responseBody}")
      }
    }
  }

  @Test
  fun `the refusal comes before authority and says nothing about the write context`() {
    val pod = sempodsTestFactory.newPod()
    val writable = registerContext(pod, "tasks")
    val hidden = registerContext(pod, "private")
    val absent = sempodsUriBuilder.buildContext(pod.name, "never")
    val token = mintScopedToken(pod.name, listOf("$writable#read", "$writable#write"))

    // A registered context as subject and an unregistered one: the same answer, but for the IRI it echoes.
    val subjects = listOf(writable.toString(), "${namespace(pod)}never")
    val answers = subjects.flatMap { subject ->
      listOf(writable, hidden, absent).flatMap { context ->
        additions(pod, subject, context, token).map { (label, response) ->
          assertRefusedForTheNamespace(pod, "$label <$subject> in <$context>", response)
          label to response.responseBody.replace(subject, "<subject>")
        }
      }
    }
    answers.groupBy({ it.first }, { it.second }).forEach { (label, bodies) ->
      assertEquals(1, bodies.toSet().size, "$label answers alike whatever the contexts' state: $bodies")
    }
  }

  @Test
  fun `the removals still reach statements stored under the namespace`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val subject = "$tasks/res-1"
    val friend = "$api${pod.name}/people/bob"

    // Stored before the pod refused such writes. The routes cannot write it any more, so the store does.
    val stored = LinkedHashModel().apply {
      add(Values.iri(subject), Values.iri(schemaName), Values.literal("Buy milk"), tasks.toIri())
      add(Values.iri(subject), Values.iri(schemaKnows), Values.iri(friend), tasks.toIri())
      add(Values.iri(subject), Values.iri("https://schema.org/description"), Values.literal("today"), tasks.toIri())
    }
    podFacade.patchResource(podName = pod.name, resourceUri = URI(subject), contextUri = tasks, replacementModel = stored)

    val read = get(resourceUrl(pod, subject, tasks), token)
    assertEquals(200, read.statusCode, read.responseBody)
    assertTrue(read.responseBody.contains("Buy milk"), read.responseBody)
    additions(pod, subject, tasks, token).forEach { (label, response) -> assertRefusedForTheNamespace(pod, label, response) }

    val edge = send("DELETE", edgeUrl(pod, subject, schemaKnows, friend, tasks), token)
    assertEquals(200, edge.statusCode, edge.responseBody)
    assertTrue(edge.responseBody.contains("\"removed\""), edge.responseBody)

    val slot = send("DELETE", slotUrl(pod, subject, schemaName, tasks), token)
    assertEquals(200, slot.statusCode, slot.responseBody)
    assertTrue(slot.responseBody.contains("\"cleared\""), slot.responseBody)

    assertEquals(204, send("DELETE", resourceUrl(pod, subject, tasks), token).statusCode)
    assertEquals(404, get(resourceUrl(pod, subject, tasks), token).statusCode)
  }
}
