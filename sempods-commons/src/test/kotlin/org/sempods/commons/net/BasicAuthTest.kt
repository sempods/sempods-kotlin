package org.sempods.commons.net

import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BasicAuthTest {

  private fun encode(username: String, password: String): String {
    val encId = URLEncoder.encode(username, Charsets.UTF_8)
    val encSecret = URLEncoder.encode(password, Charsets.UTF_8)
    return "Basic " + Base64.getEncoder().encodeToString("$encId:$encSecret".toByteArray(Charsets.UTF_8))
  }

  @Test
  fun `parse decodes plain credentials`() {
    val header = encode("alice", "secret")
    assertEquals(BasicCredentials("alice", "secret"), BasicAuth.parse(header))
  }

  @Test
  fun `parse form-decodes reserved characters in username and password`() {
    // RFC 6749 §2.3.1 (`client_secret_basic`): clients form-encode each half
    // before base64. A clientId containing `:` would otherwise split mid-id.
    val header = encode("notes:primary", "p@ss word+%")
    assertEquals(
      BasicCredentials("notes:primary", "p@ss word+%"),
      BasicAuth.parse(header),
    )
  }

  @Test
  fun `parse round-trips UTF-8 credentials`() {
    val header = encode("björn", "ümläut🙂")
    assertEquals(BasicCredentials("björn", "ümläut🙂"), BasicAuth.parse(header))
  }

  @Test
  fun `parse returns null for null header`() {
    assertNull(BasicAuth.parse(null))
  }

  @Test
  fun `parse returns null for blank header`() {
    assertNull(BasicAuth.parse("   "))
  }

  @Test
  fun `parse returns null for missing Basic scheme`() {
    val token = Base64.getEncoder().encodeToString("a:b".toByteArray(Charsets.UTF_8))
    assertNull(BasicAuth.parse("Bearer $token"))
  }

  @Test
  fun `parse accepts case-insensitive Basic scheme`() {
    val token = Base64.getEncoder().encodeToString("a:b".toByteArray(Charsets.UTF_8))
    assertEquals(BasicCredentials("a", "b"), BasicAuth.parse("basic $token"))
    assertEquals(BasicCredentials("a", "b"), BasicAuth.parse("BASIC $token"))
  }

  @Test
  fun `parse returns null when base64 payload is malformed`() {
    assertNull(BasicAuth.parse("Basic !!!not-base64!!!"))
  }

  @Test
  fun `parse returns null when decoded payload has no colon`() {
    val token = Base64.getEncoder().encodeToString("usernameonly".toByteArray(Charsets.UTF_8))
    assertNull(BasicAuth.parse("Basic $token"))
  }

  @Test
  fun `parse returns null for empty username`() {
    val token = Base64.getEncoder().encodeToString(":secret".toByteArray(Charsets.UTF_8))
    assertNull(BasicAuth.parse("Basic $token"))
  }

  @Test
  fun `parse returns null for empty password`() {
    val token = Base64.getEncoder().encodeToString("alice:".toByteArray(Charsets.UTF_8))
    assertNull(BasicAuth.parse("Basic $token"))
  }

  @Test
  fun `parse returns null for malformed percent-encoding in either half`() {
    // `%FF` is a valid byte but `%FZ` is malformed — URLDecoder throws,
    // [UrlUtil.urlDecodeOrNull] returns null, and parse() propagates that.
    val token = Base64.getEncoder().encodeToString("a%FZ:secret".toByteArray(Charsets.UTF_8))
    assertNull(BasicAuth.parse("Basic $token"))
  }

  @Test
  fun `parse tolerates blank password before form-decode (still rejected)`() {
    // Username `a` is non-blank, "password" is `+` which form-decodes to a
    // single space — empty password rule is `isEmpty()`, not `isBlank()`, so
    // a space-only password is accepted. Documents the intentional asymmetry.
    val token = Base64.getEncoder().encodeToString("a:+".toByteArray(Charsets.UTF_8))
    assertEquals(BasicCredentials("a", " "), BasicAuth.parse("Basic $token"))
  }
}
