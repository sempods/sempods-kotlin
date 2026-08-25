package org.sempods.commons.jaxrs

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for content-type-aware ETag generation.
 *
 * ETags should differ for different content types to prevent cache confusion where
 * a client with a cached representation of one content type (e.g., JSON) might
 * incorrectly match against a different content type (e.g., N-Quads) of the same resource.
 *
 * While the Vary: Accept header informs caches that responses may vary based on the
 * Accept header, the ETag itself must be unique per representation as per HTTP
 * specifications (RFC 7232, RFC 9110).
 */
class ContentTypeAwareETagTest : BaseEndpoint() {

  @Test
  fun `ETags should differ for different content types with same base value`() {
    val baseValue = "1234567890"
    val jsonETag = createContentTypeAwareEntityTag(baseValue, "application/json")
    val nquadsETag = createContentTypeAwareEntityTag(baseValue, "application/n-quads")
    val jsonLdETag = createContentTypeAwareEntityTag(baseValue, "application/ld+json")

    // All ETags should be different
    assertNotEquals(jsonETag.value, nquadsETag.value, "JSON and N-Quads ETags should differ")
    assertNotEquals(jsonETag.value, jsonLdETag.value, "JSON and JSON-LD ETags should differ")
    assertNotEquals(jsonLdETag.value, nquadsETag.value, "JSON-LD and N-Quads ETags should differ")
  }

  @Test
  fun `ETags should be identical for same base value and content type`() {
    val baseValue = "1234567890"
    val contentType = "application/json"

    val etag1 = createContentTypeAwareEntityTag(baseValue, contentType)
    val etag2 = createContentTypeAwareEntityTag(baseValue, contentType)

    assertEquals(etag1.value, etag2.value, "Same inputs should produce identical ETags")
  }

  @Test
  fun `ETags should differ when base value changes`() {
    val contentType = "application/json"
    val etag1 = createContentTypeAwareEntityTag("1234567890", contentType)
    val etag2 = createContentTypeAwareEntityTag("9876543210", contentType)

    assertNotEquals(etag1.value, etag2.value, "Different base values should produce different ETags")
  }

  @Test
  fun `ETags should contain base value information`() {
    val baseValue = "1234567890"
    val contentType = "application/json"
    val etag = createContentTypeAwareEntityTag(baseValue, contentType)

    // ETag value should contain the base value (timestamp)
    assert(etag.value.contains(baseValue)) {
      "ETag ${etag.value} should contain base value $baseValue"
    }
  }

  @Test
  fun `ETags should be stable across invocations`() {
    // Test that the same inputs always produce the same output (no random elements)
    val baseValue = "1234567890"
    val contentType = "application/ld+json"

    val results = (1..10).map {
      createContentTypeAwareEntityTag(baseValue, contentType).value
    }

    // All results should be identical
    assertEquals(1, results.distinct().size, "ETag generation should be deterministic")
  }

  @Test
  fun `ETag format should be reasonable for HTTP headers`() {
    val baseValue = "1234567890"
    val contentType = "application/json"
    val etag = createContentTypeAwareEntityTag(baseValue, contentType)

    // Should not be too long
    assert(etag.value.length < 100) { "ETag should be reasonably short: ${etag.value}" }

    // Should not contain problematic characters
    assert(!etag.value.contains("\"")) { "ETag should not contain quotes: ${etag.value}" }
    assert(!etag.value.contains("\n")) { "ETag should not contain newlines: ${etag.value}" }
  }
}
