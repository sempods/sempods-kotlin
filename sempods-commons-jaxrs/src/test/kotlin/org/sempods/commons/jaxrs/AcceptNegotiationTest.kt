package org.sempods.commons.jaxrs

import jakarta.ws.rs.core.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** What an `Accept` header asks for, case by case from RFC 9110 §12.5.1. */
class AcceptNegotiationTest {

  private val jsonLd = MediaType.valueOf("application/ld+json;charset=utf-8")

  private val nQuads = MediaType.valueOf("application/n-quads;charset=utf-8")

  private val json = MediaType.valueOf("application/json;charset=utf-8")

  /** In the order a route prefers them. */
  private val available = listOf(jsonLd, nQuads, json)

  private fun select(header: String?) = AcceptNegotiation.select(header, available)

  @Test
  fun `a caller expressing no preference gets the first representation`() {
    assertEquals(jsonLd, select(null))
    assertEquals(jsonLd, select(""))
    assertEquals(jsonLd, select("*/*"))
    assertEquals(jsonLd, select("application/*"))
  }

  @Test
  fun `quality decides, and the order of the representations breaks a tie`() {
    assertEquals(jsonLd, select("application/n-quads;q=0.5, application/ld+json"))
    assertEquals(nQuads, select("application/n-quads, application/ld+json;q=0.5"))
    assertEquals(jsonLd, select("application/ld+json, application/n-quads"))
    assertEquals(json, select("application/json"))
  }

  @Test
  fun `a range at zero excludes what it names, even beside a wildcard`() {
    assertEquals(nQuads, select("*/*, application/ld+json;q=0"))
    assertEquals(json, select("*/*, application/ld+json;q=0, application/n-quads;q=0"))
    assertNull(select("*/*;q=0"))
    assertNull(select("application/ld+json;q=0, application/n-quads;q=0, application/json;q=0"))
  }

  @Test
  fun `a parameter the representation carries keeps matching, one it does not carry names something else`() {
    assertEquals(json, select("application/json;charset=utf-8"))
    assertEquals(json, select("application/json;CharSet=UTF-8"))
    // The profiled range names a representation this caller does not offer, so it neither selects
    // nor excludes; what is left excludes everything.
    assertNull(
      select(
        "application/ld+json;profile=\"https://example.org/p\", application/ld+json;q=0, " +
          "application/n-quads;q=0, application/json;q=0",
      ),
    )
    // …and on its own it simply matches nothing.
    assertNull(select("application/ld+json;profile=\"https://example.org/p\""))
  }

  @Test
  fun `a parameter after the weight is an accept extension and names nothing`() {
    assertEquals(jsonLd, select("application/ld+json;q=1;foo=bar"))
    assertEquals(nQuads, select("application/ld+json;q=0;foo=bar, application/n-quads;q=1;bar=baz"))
  }

  @Test
  fun `a separator inside a quoted parameter belongs to the parameter`() {
    val ranges = AcceptNegotiation.parse("application/ld+json;profile=\"a,b;c\", application/n-quads")

    assertEquals(2, ranges.size)
    assertEquals("a,b;c", ranges.first().mediaType.parameters["profile"])
    assertEquals("application/n-quads", ranges.last().mediaType.toString())
  }

  @Test
  fun `a range that names no media type is dropped`() {
    assertEquals(jsonLd, select("garbage, */*"))
    assertEquals(jsonLd, select("garbage"))
    assertEquals(listOf(1.0), AcceptNegotiation.parse("garbage, application/json").map { it.quality })
  }

  @Test
  fun `the weight is read as written`() {
    assertEquals(listOf(0.8, 0.0, 1.0), AcceptNegotiation.parse("a/b;q=0.8, c/d;q=0, e/f").map { it.quality })
    // A weight that is not a number at all is no weight: the range stays fully acceptable.
    assertEquals(listOf(1.0), AcceptNegotiation.parse("a/b;q=high").map { it.quality })
  }
}
