package org.sempods.commons.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.test.assertEquals

/**
 * Encoder and decoder are tested as one pair on purpose: the decoder's contract is "what the
 * encoder produces, and nothing else", so a case that exercises only one of them says nothing
 * about whether a path segment survives the round trip a pod actually puts it through.
 */
class UriEncodingUtilTest {

  @Test
  fun `encodes a simple URI to the expected segment`() {
    val uri = URI.create("https://api.example.com/v1/events/abc123")

    // Pinned rather than round-tripped: this string appears in URLs, so it is a wire format and a
    // second implementation has to produce the same bytes.
    assertEquals(
      "aHR0cHM6Ly9hcGkuZXhhbXBsZS5jb20vdjEvZXZlbnRzL2FiYzEyMw",
      UriEncodingUtil.encodeUriToUrlSafeBase64(uri),
    )
  }

  @Test
  fun `encoded output is a single path segment`() {
    val uri = URI.create("https://example.com/path?param=value&other=123")
    val encoded = UriEncodingUtil.encodeUriToUrlSafeBase64(uri)

    // `/` would end the segment and `+` and `=` survive a router differently depending on where
    // they sit, which is the whole reason this is base64url and not base64.
    assert(encoded.matches(Regex("^[A-Za-z0-9_-]+$"))) { "not a base64url segment: $encoded" }
  }

  @Test
  fun `round trips URIs the encoder is pointed at`() {
    val testUris = listOf(
      "https://api.example.com/v1/events/abc123",
      "https://example.com/path?param1=value1&param2=value2",
      "https://example.com/path#section",
      "http://localhost:8080/test",
      "https://example.com/event/café-☕",
      "https://example.com/path/with/多くの/unicode/文字",
      "https://example.com/émoji/🎉/test",
      // A payload whose standard-Base64 form carries `+` and `/`, so the base64url alphabet is
      // exercised rather than assumed.
      "https://example.com/x?q=1+2/3",
    )

    testUris.forEach { uriString ->
      val uri = URI.create(uriString)
      val encoded = UriEncodingUtil.encodeUriToUrlSafeBase64(uri)
      assertEquals(uri, UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(encoded), "round trip: $uriString")
    }
  }

  @Test
  fun `round trips external URI schemes`() {
    listOf(
      URI.create("did:web:bob.example"),
      URI.create("urn:isbn:0451450523"),
      URI.create("mailto:bob@example.com"),
    ).forEach { uri ->
      val encoded = UriEncodingUtil.encodeUriToUrlSafeBase64(uri)
      assertEquals(uri, UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(encoded))
    }
  }

  // --- what the decoder refuses ---------------------------------------------------------------
  //
  // Each of these decodes to a perfectly good URI under a tolerant decoder, which is exactly why
  // they are refused: a second spelling of a segment is a second identity for one resource.

  @Test
  fun `rejects null and empty input`() {
    assertThrows<IllegalArgumentException> { UriEncodingUtil.decodeUrlSafeBase64ToUriStrict(null) }
    assertThrows<IllegalArgumentException> { UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("") }
  }

  @Test
  fun `rejects the standard Base64 alphabet`() {
    assertThrows<IllegalArgumentException> {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("aGVsbG8+d29ybGQ")
    }
    assertThrows<IllegalArgumentException> {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("aGVsbG8/d29ybGQ")
    }
  }

  @Test
  fun `rejects padding`() {
    // The padded form of "https://x.example/a" — valid base64, and not what the encoder emits.
    assertThrows<IllegalArgumentException> {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("aHR0cHM6Ly94LmV4YW1wbGUvYQ==")
    }
  }

  @Test
  fun `rejects whitespace and garbage`() {
    assertThrows<IllegalArgumentException> {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("aHR0cHM6 Ly94")
    }
    assertThrows<IllegalArgumentException> {
      UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("not!valid!base64url")
    }
  }

  @Test
  fun `rejects a length base64url cannot have`() {
    // length mod 4 == 1 is structurally impossible for unpadded base64url
    assertThrows<IllegalArgumentException> { UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("A") }
    assertThrows<IllegalArgumentException> { UriEncodingUtil.decodeUrlSafeBase64ToUriStrict("AAAAA") }
  }
}
