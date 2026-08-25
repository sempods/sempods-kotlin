package org.sempods.commons.net

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals

class UrlUtilTest {

  // --- queryParams ---

  @Test
  fun `queryParams returns empty map for null query`() {
    assertEquals(emptyMap(), UrlUtil.queryParams(null))
  }

  @Test
  fun `queryParams parses simple key-value pairs`() {
    val params = UrlUtil.queryParams("a=1&b=2")
    assertEquals(mapOf("a" to "1", "b" to "2"), params)
  }

  @Test
  fun `queryParams decodes percent-encoded keys and values by default`() {
    val params = UrlUtil.queryParams("client_id=did%3Aweb%3Aapps.localhost%3Afocus")
    assertEquals("did:web:apps.localhost:focus", params["client_id"])
  }

  @Test
  fun `queryParams preserves raw encoding when decodeParams is false`() {
    val params = UrlUtil.queryParams("client_id=did%3Aweb%3Aapps.localhost%3Afocus", decodeParams = false)
    assertEquals("did%3Aweb%3Aapps.localhost%3Afocus", params["client_id"])
  }

  @Test
  fun `queryParams handles key without value`() {
    val params = UrlUtil.queryParams("flag")
    assertEquals(mapOf("flag" to ""), params)
  }

  @Test
  fun `queryParams handles empty value`() {
    val params = UrlUtil.queryParams("key=")
    assertEquals(mapOf("key" to ""), params)
  }

  @Test
  fun `queryParams handles value containing equals sign`() {
    val params = UrlUtil.queryParams("data=a=b=c")
    assertEquals("a=b=c", params["data"])
  }

  // --- addOrUpdateQueryParameter ---

  @Test
  fun `addOrUpdateQueryParameter adds param to URI without query`() {
    val uri = URI("http://localhost/path")
    val result = UrlUtil.addOrUpdateQueryParameter(uri, "key", "value")
    assertEquals("value", result.decodedParams()["key"])
  }

  @Test
  fun `addOrUpdateQueryParameter adds param to URI with existing query`() {
    val uri = URI("http://localhost/path?existing=1")
    val result = UrlUtil.addOrUpdateQueryParameter(uri, "key", "value")
    assertEquals("1", result.decodedParams()["existing"])
    assertEquals("value", result.decodedParams()["key"])
  }

  @Test
  fun `addOrUpdateQueryParameter updates existing param`() {
    val uri = URI("http://localhost/path?key=old")
    val result = UrlUtil.addOrUpdateQueryParameter(uri, "key", "new")
    assertEquals("new", result.decodedParams()["key"])
  }

  @Test
  fun `addOrUpdateQueryParameter encodes special characters in value`() {
    val uri = URI("http://localhost/path")
    val result = UrlUtil.addOrUpdateQueryParameter(uri, "q", "hello world&more=stuff")
    assertEquals("hello world&more=stuff", result.decodedParams()["q"])
  }

  @Test
  fun `addOrUpdateQueryParameter preserves scheme, authority, path, and fragment`() {
    val uri = URI("https://example.org:8080/some/path?old=1#frag")
    val result = UrlUtil.addOrUpdateQueryParameter(uri, "new", "2")
    assertEquals("https", result.scheme)
    assertEquals("example.org:8080", result.authority)
    assertEquals("/some/path", result.path)
    assertEquals("frag", result.fragment)
  }

  @Test
  fun `addOrUpdateQueryParameter handles URL as value without double-encoding`() {
    val base = URI("http://localhost:8091/login")
    val returnTo = "http://localhost:8090/test/_system/auth/authorize?client_id=did:web:apps.localhost:focus&redirect_uri=http://localhost:3000/callback&state=abc"

    val result = UrlUtil.addOrUpdateQueryParameter(base, "return_to", returnTo)

    // The return_to value must round-trip: decode the query to get the original URL back
    assertEquals(returnTo, result.decodedParams()["return_to"])

    // No double-encoding in raw query
    assertEquals(-1, result.rawQuery.indexOf("%25"), "rawQuery must not contain double-encoded chars: ${result.rawQuery}")
  }

  @Test
  fun `addOrUpdateQueryParameter preserves existing encoded params when adding new`() {
    // URI with encoded query params (as seen when browser sends did:web: values)
    val uri = URI("http://localhost:8090/auth?client_id=did%3Aweb%3Aapps.localhost%3Afocus&state=abc")

    val result = UrlUtil.addOrUpdateQueryParameter(uri, "token", "eyJhbGciOiJSUzI1NiJ9")

    // Existing params must survive without double-encoding
    assertEquals("did:web:apps.localhost:focus", result.decodedParams()["client_id"])
    assertEquals("abc", result.decodedParams()["state"])
    assertEquals("eyJhbGciOiJSUzI1NiJ9", result.decodedParams()["token"])
  }

  @Test
  fun `addOrUpdateQueryParameter - real authorize flow - no double-encoding in return_to`() {
    // Simulates the real flow:
    // 1. Browser sends authorize request with encoded params
    // 2. Server builds return_to using toDecodedString()
    // 3. return_to is passed to addOrUpdateQueryParameter
    val authorizeUri = URI("http://localhost:8090/test/_system/auth/authorize?client_id=did%3Aweb%3Aapps.localhost%3Afocus&redirect_uri=http%3A%2F%2Flocalhost%3A3000%2Ffocus%2F_shell%2Fcallbacks%2Foauth&state=xyz")

    // toDecodedString gives the decoded URL (as the caller should use)
    val returnTo = UrlUtil.toDecodedString(authorizeUri)
    assertEquals(
      "http://localhost:8090/test/_system/auth/authorize?client_id=did:web:apps.localhost:focus&redirect_uri=http://localhost:3000/focus/_shell/callbacks/oauth&state=xyz",
      returnTo,
    )

    val loginUri = URI("http://localhost:8091/login")
    val result = UrlUtil.addOrUpdateQueryParameter(loginUri, "return_to", returnTo)

    // Decoded return_to must equal the decoded authorize URL
    assertEquals(returnTo, result.decodedParams()["return_to"])

    // No double-encoding (%25 must not appear)
    assertEquals(-1, result.rawQuery.indexOf("%25"), "rawQuery must not contain double-encoded chars: ${result.rawQuery}")
  }

  // --- toDecodedString ---

  @Test
  fun `toDecodedString decodes percent-encoded query params`() {
    val uri = URI("http://localhost:8090/path?client_id=did%3Aweb%3Aapps.localhost%3Afocus&state=abc")
    val decoded = UrlUtil.toDecodedString(uri)
    assertEquals("http://localhost:8090/path?client_id=did:web:apps.localhost:focus&state=abc", decoded)
  }

  @Test
  fun `toDecodedString preserves URI without query`() {
    val uri = URI("http://localhost:8090/path")
    assertEquals("http://localhost:8090/path", UrlUtil.toDecodedString(uri))
  }

  @Test
  fun `toDecodedString preserves fragment`() {
    val uri = URI("http://localhost/path?q=1#section")
    assertEquals("http://localhost/path?q=1#section", UrlUtil.toDecodedString(uri))
  }

  // --- urlEncode / urlDecode round-trip ---

  @Test
  fun `urlEncode and urlDecode round-trip`() {
    val original = "did:web:apps.localhost:focus/path?q=hello world&x=1"
    assertEquals(original, UrlUtil.urlDecode(UrlUtil.urlEncode(original)))
  }

  // --- urlDecodeOrNull ---

  @Test
  fun `urlDecodeOrNull decodes well-formed input identically to urlDecode`() {
    val encoded = "did%3Aweb%3Aapps.localhost%3Afocus"
    assertEquals(UrlUtil.urlDecode(encoded), UrlUtil.urlDecodeOrNull(encoded))
  }

  @Test
  fun `urlDecodeOrNull returns null for malformed percent-encoding`() {
    // `%FZ` is a syntactically invalid percent escape (Z is not a hex digit).
    kotlin.test.assertNull(UrlUtil.urlDecodeOrNull("foo%FZbar"))
  }

  /** Helper: parse decoded query params from a URI using rawQuery */
  private fun URI.decodedParams(): Map<String, String> =
    UrlUtil.queryParams(this.rawQuery, decodeParams = true)
}
