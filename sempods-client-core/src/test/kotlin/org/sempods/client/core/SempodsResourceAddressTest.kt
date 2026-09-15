package org.sempods.client.core

import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** The path each group sends for an IRI, and the IRIs a resource's own address cannot carry. */
class SempodsResourceAddressTest {

  private val alice = ResourceAddress.LodPath(SempodsPodBase.of("https://pods.example/alice"))

  @Test
  fun `a resource's path is its IRI's path under the pod, encoded where it is not ASCII`() {
    assertEquals("events/1", alice.path("https://pods.example/alice/events/1"))
    assertEquals("events/gr%C3%BC%C3%9Fe", alice.path("https://pods.example/alice/events/grüße"))
    assertEquals("notes/", alice.path("https://pods.example/alice/notes/"))
    assertEquals("a!\$&'()*+,=:@-._~b", alice.path("https://pods.example/alice/a!\$&'()*+,=:@-._~b"))
    assertEquals("_systemx/a", alice.path("https://pods.example/alice/_systemx/a"))
    assertEquals("events/_system/.well-known", alice.path("https://pods.example/alice/events/_system/.well-known"))
    assertEquals("..a/.b", alice.path("https://pods.example/alice/..a/.b"))
  }

  @Test
  fun `a pod at the host root and a nested pod address their resources the same way`() {
    assertEquals("events/1", ResourceAddress.LodPath(SempodsPodBase.of("https://pods.example")).path("https://pods.example/events/1"))
    assertEquals(
      "events/1",
      ResourceAddress.LodPath(SempodsPodBase.of("https://example.org/pods/alice/")).path("https://example.org/pods/alice/events/1"),
    )
    assertEquals("%C3%BC/", ResourceAddress.LodPath(SempodsPodBase.of("http://127.0.0.1:8080/alice")).path("http://127.0.0.1:8080/alice/ü/"))
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "https://pods.example/bob/events/1",
      "https://pods.example/alice-archive/events/1",
      "http://pods.example/alice/events/1",
      "https://pods.example:8443/alice/events/1",
      "https://other.example/alice/events/1",
      "did:web:bob.example",
      "",
    ],
  )
  fun `an IRI outside the pod is refused and pointed to subjects`(iri: String) {
    val refused = assertThrows<IllegalArgumentException> { alice.path(iri) }

    assertEquals("'$iri' is not under the pod 'https://pods.example/alice'; it is reached through subjects().", refused.message)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "https://pods.example/alice",
      "https://pods.example/alice/",
      "https://pods.example/alice/events/1?view=full",
      "https://pods.example/alice/events/1#me",
      "https://pods.example/alice/events/a%20b",
      "https://pods.example/alice/events/a%2Fb",
      "https://pods.example/alice/events/a;b",
      "https://pods.example/alice/events/a;jsessionid=1",
      "https://pods.example/alice/events/a b",
      "https://pods.example/alice/events/a\\b",
      "https://pods.example/alice/events/a\"b",
      "https://pods.example/alice/events/a<b>",
      "https://pods.example/alice/events/a^b",
      "https://pods.example/alice/events/a`b",
      "https://pods.example/alice/events/a{b}",
      "https://pods.example/alice/events/a|b",
      "https://pods.example/alice/events/a[b]",
      "https://pods.example/alice/events/a\tb",
      "https://pods.example/alice/events/ab",
      "https://pods.example/alice/_system",
      "https://pods.example/alice/_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ",
      "https://pods.example/alice/.well-known",
      "https://pods.example/alice/.well-known/sempods",
      "https://pods.example/alice/./events",
      "https://pods.example/alice/events/..",
      "https://pods.example/alice/events/../../bob",
      "https://pods.example/alice//events",
      "https://pods.example/alice/events//1",
    ],
  )
  fun `an IRI under the pod that its own path cannot name as it is is refused and pointed to subjects`(iri: String) {
    val message = assertThrows<IllegalArgumentException> { alice.path(iri) }.message.orEmpty()

    assertTrue(message.startsWith("'$iri' ") && message.endsWith("; it is reached through subjects()."), message)
    assertFalse("is not under the pod" in message, message)
  }

  @Test
  fun `each refusal says what the path cannot carry`() {
    fun reason(iri: String) =
      assertThrows<IllegalArgumentException> { alice.path(iri) }.message.orEmpty()
        .removePrefix("'$iri' ").removeSuffix("; it is reached through subjects().")

    assertEquals("is the pod's base URL, with no path under it", reason("https://pods.example/alice/"))
    assertEquals("has a query or a fragment", reason("https://pods.example/alice/a?b"))
    assertEquals("has a character at position 28 that its path under the pod cannot carry as it is", reason("https://pods.example/alice/a%20b"))
    assertEquals("lies in the pod's reserved '_system' area (SPS-CRUD-004)", reason("https://pods.example/alice/_system/x"))
    assertEquals("lies in the pod's reserved '.well-known' area (SPS-CRUD-004)", reason("https://pods.example/alice/.well-known"))
    assertEquals("has a dot segment", reason("https://pods.example/alice/a/../b"))
    assertEquals("has an empty segment", reason("https://pods.example/alice/a//b"))
  }

  @ParameterizedTest
  @CsvSource(
    delimiter = '|',
    value = [
      "did:web:bob.example                     | ZGlkOndlYjpib2IuZXhhbXBsZQ",
      "urn:isbn:978-3-16-148410-0              | dXJuOmlzYm46OTc4LTMtMTYtMTQ4NDEwLTA",
      "https://pods.example/alice/events/grüße | aHR0cHM6Ly9wb2RzLmV4YW1wbGUvYWxpY2UvZXZlbnRzL2dyw7zDn2U",
      // The standard alphabet would end these two in `+` and `/`, which a path cannot carry as they are.
      "urn:x:ab~                               | dXJuOng6YWJ-",
      "https://example.org/ü                   | aHR0cHM6Ly9leGFtcGxlLm9yZy_DvA",
    ],
  )
  fun `a subject travels as base64url without padding over its UTF-8 bytes`(iri: String, segment: String) {
    assertEquals("_system/resources/$segment", ResourceAddress.SystemRoute.path(iri))
    assertFalse('=' in segment)
    assertEquals(iri, String(Base64.getUrlDecoder().decode(segment), Charsets.UTF_8))
  }

  @Test
  fun `a blank subject is refused, and any other string is left to the pod`() {
    listOf("", " ", "\n").forEach { blank ->
      assertEquals("A subject IRI must not be blank.", assertThrows<IllegalArgumentException> { ResourceAddress.SystemRoute.path(blank) }.message)
    }
    assertEquals("_system/resources/bm90IGFuIElSSQ", ResourceAddress.SystemRoute.path("not an IRI"))
  }

  @Test
  fun `a slot and an edge extend the subject's route by one base64url segment each`() {
    assertEquals(
      "_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ/aHR0cDovL3htbG5zLmNvbS9mb2FmLzAuMS9rbm93cw",
      ResourceAddress.SystemRoute.slotPath("did:web:bob.example", "http://xmlns.com/foaf/0.1/knows"),
    )
    assertEquals(
      "_system/resources/ZGlkOndlYjpib2IuZXhhbXBsZQ/aHR0cHM6Ly9zY2hlbWEub3JnL2NoaWxkcmVu/aHR0cHM6Ly9leGFtcGxlLm9yZy_DvA",
      ResourceAddress.SystemRoute.edgePath("did:web:bob.example", "https://schema.org/children", "https://example.org/ü"),
    )
    assertEquals(
      "A predicate IRI must not be blank.",
      assertThrows<IllegalArgumentException> { ResourceAddress.SystemRoute.slotPath("did:web:bob.example", " ") }.message,
    )
    assertEquals(
      "A target IRI must not be blank.",
      assertThrows<IllegalArgumentException> { ResourceAddress.SystemRoute.edgePath("did:web:bob.example", "https://schema.org/name", "") }.message,
    )
  }
}
