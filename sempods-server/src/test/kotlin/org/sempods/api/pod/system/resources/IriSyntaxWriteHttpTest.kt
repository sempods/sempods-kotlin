package org.sempods.api.pod.system.resources

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.mongo.persist.PodDbo
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An IRI that is not an absolute RFC 3987 IRI answers `400` and is named: as the resource a route
 * addresses, whatever the verb, and in a write body. The rule is [org.sempods.pods.IriSyntax];
 * reads keep dropping a malformed `?context=` in silence.
 */
class IriSyntaxWriteHttpTest : SempodsIntegrationTest() {

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

  // Straight from the bytes: `java.net.URI` refuses `%zz`, and the test must still be able to send it.
  private fun b64(iri: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(iri.toByteArray(StandardCharsets.UTF_8))

  private fun inContext(url: String, context: String) =
    "$url?context=${URLEncoder.encode(context, StandardCharsets.UTF_8)}"

  private fun resourceUrl(pod: PodDbo, iri: String, context: URI) = inContext("$api${pod.name}/_system/resources/${b64(iri)}", context.toString())

  private fun slotUrl(pod: PodDbo, subject: String, predicate: String, context: URI) =
    inContext("$api${pod.name}/_system/resources/${b64(subject)}/${b64(predicate)}", context.toString())

  private fun send(method: String, url: String, token: String, body: String? = null, contentType: String = "application/ld+json") =
    http.prepare(method, url)
      .addHeader("Authorization", "Bearer $token")
      .apply { body?.let { addHeader("Content-Type", contentType).setBody(it) } }
      .execute()

  private fun get(url: String, token: String) =
    http.prepareGet(url).addHeader("Accept", "application/ld+json").addHeader("Authorization", "Bearer $token").execute()

  /** JSON string escaping for the code points under test, so the body itself is valid JSON. */
  private fun json(s: String) = s.map { if (it.code > 0x7E) "\\u%04X".format(it.code) else it.toString() }.joinToString("")

  private fun assertRefused(label: String, response: TestHttpResponse) {
    assertEquals(400, response.statusCode, "$label: ${response.responseBody}")
    assertTrue(response.responseBody.contains("is not an absolute RFC 3987 IRI"), "$label names the rule: ${response.responseBody}")
  }

  /** A `400` from a check that runs before [org.sempods.pods.IriSyntax] and words it its own way. */
  private fun assertBadRequest(label: String, response: TestHttpResponse) =
    assertEquals(400, response.statusCode, "$label: ${response.responseBody}")

  /** The four writes that add statements about [subject]: resource `PUT` and `PATCH`, slot `PUT` and `POST`. */
  private fun additions(pod: PodDbo, subject: String, context: URI, token: String): Map<String, TestHttpResponse> = mapOf(
    "PUT resource" to send("PUT", resourceUrl(pod, subject, context), token, """{"@id":"${json(subject)}","$schemaName":"a note"}"""),
    "PATCH resource" to send(
      "PATCH", resourceUrl(pod, subject, context), token, """{"$schemaName":[{"@value":"a note"}]}""", "application/merge-patch+json",
    ),
    "PUT slot" to send("PUT", slotUrl(pod, subject, schemaName, context), token, """[{"@value":"a note"}]"""),
    "POST slot" to send("POST", slotUrl(pod, subject, schemaName, context), token, """{"@value":"a note"}"""),
  )

  @Test
  fun `every route about a subject outside RFC 3987 answers 400, reads and removals included`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))

    listOf(
      "$api${pod.name}/notes/a", // private use
      "$api${pod.name}/notes/a￾", // non-character
      "$api${pod.name}/notes/a￰", // specials
    ).forEach { subject ->
      additions(pod, subject, tasks, token).forEach { (label, response) -> assertRefused("$label <$subject>", response) }
      assertRefused("GET resource <$subject>", get(resourceUrl(pod, subject, tasks), token))
      assertRefused("DELETE resource <$subject>", send("DELETE", resourceUrl(pod, subject, tasks), token))
      assertRefused("DELETE slot <$subject>", send("DELETE", slotUrl(pod, subject, schemaName, tasks), token))
    }
    // The b64 segment decodes to a `java.net.URI`, which refuses a malformed escape first.
    val percent = "$api${pod.name}/notes/a%zz"
    additions(pod, percent, tasks, token).forEach { (label, response) -> assertBadRequest("$label <$percent>", response) }
  }

  @Test
  fun `a slot write with a predicate outside RFC 3987 answers 400`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val subject = "$api${pod.name}/notes/a"
    val predicate = "https://schema.org/name"

    assertRefused("PUT slot", send("PUT", slotUrl(pod, subject, predicate, tasks), token, """[{"@value":"a note"}]"""))
    assertRefused("POST slot", send("POST", slotUrl(pod, subject, predicate, tasks), token, """{"@value":"a note"}"""))
    assertEquals(404, get(resourceUrl(pod, subject, tasks), token).statusCode)
  }

  @Test
  fun `a slot value whose @id or @type is outside RFC 3987 answers 400`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val subject = "$api${pod.name}/notes/a"

    // `Values.iri` applies the same rule here, in its own words.
    mapOf(
      "PUT @id" to send("PUT", slotUrl(pod, subject, schemaKnows, tasks), token, """[{"@id":"https://example.org/b"}]"""),
      "POST @id" to send("POST", slotUrl(pod, subject, schemaKnows, tasks), token, """{"@id":"/b:c"}"""),
      "PUT @type" to send(
        "PUT", slotUrl(pod, subject, schemaName, tasks), token, """[{"@value":"x","@type":"https://example.org/t￾"}]""",
      ),
      "POST @type" to send("POST", slotUrl(pod, subject, schemaName, tasks), token, """{"@value":"x","@type":"https://example.org/t%zz"}"""),
    ).forEach { (label, response) -> assertBadRequest(label, response) }
    assertEquals(404, get(resourceUrl(pod, subject, tasks), token).statusCode)
  }

  @Test
  fun `a resource body with an IRI outside RFC 3987 answers 400, whichever parser read it`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val subject = "$api${pod.name}/notes/a"
    val url = resourceUrl(pod, subject, tasks)

    // The JSON-LD parser lets a datatype through, the N-Quads parser a relative reference.
    assertRefused(
      "JSON-LD datatype",
      send("PUT", url, token, """{"@id":"$subject","$schemaName":{"@value":"x","@type":"${json("https://example.org/t")}"}}"""),
    )
    assertRefused("N-Quads object", send("PUT", url, token, "<$subject> <$schemaKnows> </b:c> .\n", "application/n-quads"))
    assertRefused("N-Quads predicate", send("PUT", url, token, "<$subject> </p:q> \"x\" .\n", "application/n-quads"))
    // An object the JSON-LD parser refuses itself is a 400 in the parser's words.
    assertBadRequest(
      "JSON-LD object",
      send("PUT", url, token, """{"@id":"$subject","$schemaKnows":{"@id":"${json("https://example.org/b")}"}}"""),
    )
    assertEquals(404, get(url, token).statusCode)
  }

  @Test
  fun `the LOD path answers 400 for a decoded path outside RFC 3987`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val body = """{"$schemaName":"a note"}"""

    val privateUse = send("PUT", inContext("$api${pod.name}/notes/a%EE%80%80", tasks.toString()), token, body)
    assertRefused("U+E000 in the path", privateUse)
    listOf("GET", "OPTIONS").forEach { method ->
      assertRefused("$method with U+E000 in the path", send(method, "$api${pod.name}/notes/a%EE%80%80", token))
    }
    assertRefused("OPTIONS on the b64 route", send("OPTIONS", "$api${pod.name}/_system/resources/${b64("$api${pod.name}/notes/a")}", token))
    // Decoded, `%25zz` is `%zz`, which is not a URI at all.
    assertBadRequest("%zz in the path", send("PUT", inContext("$api${pod.name}/notes/a%25zz", tasks.toString()), token, body))
  }

  @Test
  fun `an umlaut and a supplementary character still write`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))

    listOf("$api${pod.name}/notes/Müller", "$api${pod.name}/notes/𠀀").forEach { subject ->
      val put = send("PUT", resourceUrl(pod, subject, tasks), token, """{"@id":"${json(subject)}","$schemaName":"a note"}""")
      assertEquals(201, put.statusCode, "<$subject>: ${put.responseBody}")
      val slot = send("POST", slotUrl(pod, subject, schemaKnows, tasks), token, """{"@id":"${json("$api${pod.name}/people/Jürgen")}"}""")
      assertEquals(201, slot.statusCode, "<$subject>: ${slot.responseBody}")
      assertEquals(200, get(resourceUrl(pod, subject, tasks), token).statusCode, "<$subject> was stored")
    }
  }

  @Test
  fun `a write context outside RFC 3987 answers 400, and a read drops it in silence`() {
    val pod = sempodsTestFactory.newPod()
    val tasks = registerContext(pod, "tasks")
    val token = mintScopedToken(pod.name, listOf("$tasks#read", "$tasks#write"))
    val subject = "$api${pod.name}/notes/a"
    val malformed = "$tasks"

    val write = send(
      "PUT", inContext("$api${pod.name}/_system/resources/${b64(subject)}", malformed), token,
      """{"@id":"$subject","$schemaName":"a note"}""",
    )
    assertRefused("write ?context=", write)

    assertEquals(201, send("PUT", resourceUrl(pod, subject, tasks), token, """{"@id":"$subject","$schemaName":"a note"}""").statusCode)
    // A downscope naming only the malformed context selects nothing — the same 404 as an unreadable one.
    val read = get(inContext("$api${pod.name}/_system/resources/${b64(subject)}", malformed), token)
    assertEquals(404, read.statusCode, read.responseBody)
  }
}
