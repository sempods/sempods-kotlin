package org.sempods.client.core

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** What read options, write options and content hold, and what they refuse to hold. */
class SempodsResourceOptionsTest {

  @Test
  fun `read options start from what the session may read, merged and unconditional`() {
    val defaults = SempodsReadOptions.defaults()

    assertEquals(SempodsContextSelection.readable(), defaults.selection)
    assertFalse(defaults.includeContexts)
    assertNull(defaults.ifNoneMatch)
    assertEquals(defaults.withSelection(SempodsContextSelection.of("urn:a")), SempodsReadOptions.of(SempodsContextSelection.of("urn:a")))
  }

  @Test
  fun `each with gives new read options and leaves the ones it came from as they were`() {
    val defaults = SempodsReadOptions.defaults()
    val narrowed = defaults.withSelection(SempodsContextSelection.none()).withIncludeContexts(true).withIfNoneMatch("W/\"v1\"")

    assertEquals(SempodsContextSelection.none(), narrowed.selection)
    assertTrue(narrowed.includeContexts)
    assertEquals("W/\"v1\"", narrowed.ifNoneMatch)
    assertNull(narrowed.withIfNoneMatch(null).ifNoneMatch)
    assertEquals(SempodsReadOptions.of(SempodsContextSelection.readable()), defaults)
    assertNotEquals(defaults, narrowed)
    assertEquals(
      narrowed.hashCode(),
      SempodsReadOptions.of(SempodsContextSelection.of()).withIncludeContexts(true).withIfNoneMatch("W/\"v1\"").hashCode(),
    )
    assertEquals(
      "SempodsReadOptions(selection=SempodsContextSelection(none), includeContexts=true, ifNoneMatch=W/\"v1\")",
      narrowed.toString(),
    )
  }

  @Test
  fun `write options carry their target context and their tags as given`() {
    val unconditional = SempodsWriteOptions.inContext("urn:tasks")
    val conditional = unconditional.withIfMatch("\"abc-jsonld\"").withIfNoneMatch("*")

    assertEquals("urn:tasks", unconditional.contextUri)
    assertNull(unconditional.ifMatch)
    assertNull(unconditional.ifNoneMatch)
    assertFalse(unconditional.isConditional)
    assertEquals("\"abc-jsonld\"", conditional.ifMatch)
    assertEquals("*", conditional.ifNoneMatch)
    assertTrue(conditional.isConditional)
    assertEquals("urn:notes", conditional.withContext("urn:notes").contextUri)
    assertEquals("*", conditional.withContext("urn:notes").ifNoneMatch)
    assertEquals(unconditional, conditional.withIfMatch(null).withIfNoneMatch(null))
    assertEquals(
      conditional.hashCode(),
      SempodsWriteOptions.inContext("urn:notes").withIfNoneMatch("*").withIfMatch("\"abc-jsonld\"").withContext("urn:tasks").hashCode(),
    )
    assertEquals("SempodsWriteOptions(contextUri=urn:tasks, ifMatch=\"abc-jsonld\", ifNoneMatch=*)", conditional.toString())
  }

  @ParameterizedTest
  @ValueSource(strings = ["", " ", "\t"])
  fun `a blank context or tag is refused, and null removes a tag`(blank: String) {
    listOf<() -> Any>({ SempodsWriteOptions.inContext(blank) }, { SempodsWriteOptions.inContext("urn:tasks").withContext(blank) }).forEach {
      assertEquals("A context IRI must not be blank: every write names its target context.", assertThrows<IllegalArgumentException> { it() }.message)
    }
    listOf<() -> Any>(
      { SempodsWriteOptions.inContext("urn:tasks").withIfMatch(blank) },
      { SempodsWriteOptions.inContext("urn:tasks").withIfNoneMatch(blank) },
      { SempodsReadOptions.defaults().withIfNoneMatch(blank) },
    ).forEach {
      assertEquals("An entity tag must not be blank; pass null to send none.", assertThrows<IllegalArgumentException> { it() }.message)
    }
  }

  @Test
  fun `byte content is copied when handed over and can be sent again, and text is sent as UTF-8`() {
    val given = byteArrayOf(1, 2, 3)
    val content = SempodsContent.of(given)
    given[0] = 9

    val body = content.requestBody(JSON_LD)
    assertContentEquals(byteArrayOf(1, 2, 3), body.bytes())
    assertContentEquals(byteArrayOf(1, 2, 3), content.requestBody(JSON_LD).bytes())
    assertEquals(3, body.contentLength())
    assertFalse(body.isOneShot())

    val text = SempodsContent.of("Grüße ✓").requestBody(JSON_LD)
    assertContentEquals("Grüße ✓".toByteArray(Charsets.UTF_8), text.bytes())
    assertEquals("application/ld+json", text.contentType().toString())
  }

  @Test
  fun `stream content is read once, has no length and is left open`() {
    val closed = AtomicBoolean()
    val stream = object : ByteArrayInputStream("{}".toByteArray()) {
      override fun close() = closed.set(true)
    }
    val content = SempodsContent.of(stream)

    val body = content.requestBody(JSON_LD)
    assertTrue(body.isOneShot())
    assertEquals(-1, body.contentLength())
    assertContentEquals("{}".toByteArray(), body.bytes())
    assertFalse(closed.get())
    assertEquals(
      "This stream content was already sent; a stream can be sent once.",
      assertThrows<IllegalStateException> { content.requestBody(JSON_LD) }.message,
    )
  }

  private fun RequestBody.bytes(): ByteArray = Buffer().also { writeTo(it) }.readByteArray()

  private companion object {
    val JSON_LD = "application/ld+json".toMediaType()
  }
}
