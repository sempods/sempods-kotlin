package org.sempods.client.rdf4j

import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.util.Values.bnode
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.util.Values.literal
import org.eclipse.rdf4j.model.util.Values.tripleTerm
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.SempodsContextSelection
import org.sempods.client.SempodsDecodingException
import org.sempods.client.SempodsPod
import org.sempods.client.SempodsPodBase
import org.sempods.client.SempodsReadOptions
import org.sempods.client.SempodsSession
import org.sempods.client.SempodsWriteOptions
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the slot group puts on the wire, and what it makes of the named-graph array a pod answers with. */
class SempodsRdf4jSlotsContractTest : MockPodTest() {

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

  private val slots get() = SempodsRdf4jPod(SempodsPod(SempodsSession(SempodsPodBase.of("$origin/alice")), client)).slots()

  private val bob = "did:web:bob.example"

  private val name = "https://schema.org/name"

  private val contacts = "https://pods.example/alice/_system/contexts/contacts"

  private val notes = "https://pods.example/alice/_system/contexts/notes"

  private val slotPath = "/alice/_system/resources/${b64(bob)}/${b64(name)}"

  private fun b64(iri: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(iri.toByteArray())

  private val json = JsonMapper.builder().build()

  private fun answer(status: Int, body: ByteArray) {
    server.`when`(request()).respond(response().withStatusCode(status).withHeader("ETag", "\"v1\"").withBody(body))
  }

  @Test
  fun `a read asks for JSON-LD grouped by context in the selected contexts`() {
    answer(200, "[]".toByteArray())

    slots.getModel(bob, name, SempodsReadOptions.of(SempodsContextSelection.of(contacts, notes)))

    val read = sent.single()
    assertEquals(slotPath, read.url.encodedPath)
    assertEquals("application/ld+json", read.headers["Accept"])
    assertEquals("true", read.url.queryParameter("include_contexts"))
    assertEquals(listOf(contacts, notes), read.url.queryParameterValues("context"))
  }

  @Test
  fun `every value keeps its context, its lexical form, its language and its datatype, with the tag in lower case`() {
    // The shape the reference server answers with.
    answer(
      200,
      """
      [
        {"@id": "$contacts", "@graph": [{"@id": "$bob", "$name": [
          {"@value": "Grüezi", "@language": "de-CH"},
          {"@value": "042", "@type": "${XSD.INTEGER}"}
        ]}]},
        {"@id": "$notes", "@graph": [{"@id": "$bob", "$name": [
          {"@value": "Bob"},
          {"@id": "https://pods.example/alice/people/bob"}
        ]}]}
      ]
      """.trimIndent().toByteArray(),
    )

    val read = slots.getModel(bob, name)

    assertEquals("\"v1\"", read.headers["ETag"])
    val model = assertNotNull(read.body)
    assertEquals(4, model.size)
    assertEquals(setOf(iri(contacts), iri(notes)), model.contexts())
    assertTrue(model.all { it.subject == iri(bob) && it.predicate == iri(name) })
    val inContacts = model.filter(null, null, null, iri(contacts)).map { assertIs<Literal>(it.`object`) }
    val greeting = inContacts.single { it.language.isPresent }
    assertEquals("de-ch", greeting.language.get())
    assertEquals(literal("Grüezi", "de-CH"), greeting)
    assertEquals("042", inContacts.single { it.datatype == XSD.INTEGER }.label)
    val inNotes = model.filter(null, null, null, iri(notes)).map { it.`object` }
    assertEquals(setOf<Value>(literal("Bob"), iri("https://pods.example/alice/people/bob")), inNotes.toSet())
  }

  @ParameterizedTest
  @ValueSource(strings = ["not json", "{\"@id\": 42}", "[{\"@graph\": [{\"@id\": \"did:web:bob.example\", \"urn:p\": [{\"@value\": \"x\", \"@language\": \"not a tag\"}]}]}]"])
  fun `a body that is not the named-graph document is a decoding failure with the answer's status and headers`(body: String) {
    answer(200, body.toByteArray())

    val refused = assertThrows<SempodsDecodingException> { slots.getModel(bob, name) }

    assertEquals(200, refused.status)
    assertEquals("\"v1\"", refused.headers["ETag"])
  }

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  fun `a remote context is a decoding failure, and nothing fetches it, whatever the JVM flags say`() {
    answer(200, """{"@context": "$origin/context.jsonld", "@id": "$bob", "name": "Bob"}""".toByteArray())
    val secureMode = "org.eclipse.rdf4j.rio.jsonld_secure_mode"
    System.setProperty(secureMode, "false")
    try {
      assertThrows<SempodsDecodingException> { slots.getModel(bob, name) }
    } finally {
      System.clearProperty(secureMode)
    }

    assertEquals(listOf(slotPath), sent.map { it.url.encodedPath })
    assertTrue(server.retrieveRecordedRequests(request().withPath("/context.jsonld")).isEmpty())
  }

  @Test
  fun `malformed UTF-8 is a decoding failure`() {
    val bytes = """[{"@id": "$contacts", "@graph": [{"@id": "$bob", "$name": [{"@value": "caf""".toByteArray() +
      byteArrayOf(0xC3.toByte(), 0x28) + "\"}]}]}]".toByteArray()
    answer(200, bytes)

    assertThrows<SempodsDecodingException> { slots.getModel(bob, name) }
  }

  @Test
  fun `a write sends each value as a JSON-LD value object, with its lexical form as a string`() {
    answer(204, ByteArray(0))
    val values = listOf(
      iri("https://pods.example/alice/people/bob"),
      literal("Grüezi", "de-CH"),
      literal("042", XSD.INTEGER),
      literal("true", XSD.BOOLEAN),
      literal("Bob"),
    )

    slots.put(bob, name, values, SempodsWriteOptions.inContext(contacts).withIfMatch("\"v1\""))

    val write = sent.single()
    assertEquals("PUT", write.method)
    assertEquals(slotPath, write.url.encodedPath)
    assertEquals(listOf(contacts), write.url.queryParameterValues("context"))
    assertEquals("\"v1\"", write.headers["If-Match"])
    assertEquals(
      json.readTree(
        """
        [
          {"@id": "https://pods.example/alice/people/bob"},
          {"@value": "Grüezi", "@language": "de-CH"},
          {"@value": "042", "@type": "${XSD.INTEGER}"},
          {"@value": "true", "@type": "${XSD.BOOLEAN}"},
          {"@value": "Bob"}
        ]
        """.trimIndent(),
      ),
      json.readTree(write.body),
    )
  }

  @Test
  fun `an empty collection sends an empty array, and an addition one value object`() {
    answer(200, ByteArray(0))

    slots.put(bob, name, emptyList(), SempodsWriteOptions.inContext(contacts))
    slots.add(bob, name, iri("https://pods.example/alice/people/carol"), SempodsWriteOptions.inContext(contacts))

    assertEquals(listOf("PUT", "POST"), sent.map { it.method })
    assertEquals(json.readTree("[]"), json.readTree(sent[0].body))
    assertEquals(json.readTree("""{"@id": "https://pods.example/alice/people/carol"}"""), json.readTree(sent[1].body))
  }

  @ParameterizedTest
  @ValueSource(strings = ["blank node", "triple term", "base direction"])
  fun `a value without a value object is refused before anything is sent`(kind: String) {
    val value: Value = when (kind) {
      "blank node" -> bnode("b1")
      "triple term" -> tripleTerm(iri("urn:s"), iri("urn:p"), iri("urn:o"))
      else -> SimpleValueFactory.getInstance().createLiteral("مرحبا", "ar", Literal.BaseDirection.RTL)
    }

    assertThrows<IllegalArgumentException> { slots.add(bob, name, value, SempodsWriteOptions.inContext(contacts)) }
    assertThrows<IllegalArgumentException> { slots.put(bob, name, listOf(iri("urn:ok"), value), SempodsWriteOptions.inContext(contacts)) }

    assertTrue(sent.isEmpty())
  }
}
