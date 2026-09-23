package org.sempods.api.pod.system.auth

import org.sempods.pods.oauth.flows.PodClientMetadata
import org.sempods.pods.oauth.flows.PodRegistrationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure unit — what a registration body has to be before anything decides about it.
 *
 * The rules are RFC 7591's and the SDK applies them; what is asserted here is which of the two
 * §3.2.2 codes each failure earns, and that the projection handed on is trimmed the way the dedup
 * fingerprint has always seen it.
 */
class PodRegistrationMessagesTest {

  @Test
  fun `a body that is not there is a registration that named nothing`() {
    for (body in listOf(null, "", "   ")) {
      val read = metadata(body)

      assertEquals(PodClientMetadata(), read.client, "body=$body")
      assertEquals(emptyMap(), read.raw)
    }
  }

  @Test
  fun `a body that is not JSON is refused as client metadata`() {
    val refused = unreadable("not json at all")

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertEquals("malformed JSON body", refused.description)
  }

  @Test
  fun `the JSON literal null is a body, and not a hole in the reader`() {
    // Jackson answers it with `null` and no exception, so it reaches the non-null field this
    // projection is built from unless something says otherwise.
    val refused = unreadable("null")

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertEquals("malformed JSON body", refused.description)
  }

  @Test
  fun `the stricter reader answers first`() {
    // Two readers see this body: Jackson, for the map the registration row stores, and the SDK's
    // for the typed view. The projection is only coherent while Jackson is the one that refuses
    // first, so a body it rejects must carry its answer and not the SDK's.
    val refused = unreadable("""{redirect_uris:["https://app.example/cb"]}""")

    assertEquals("malformed JSON body", refused.description, "the SDK's JSON parser takes unquoted keys")
  }

  @Test
  fun `a member of the wrong type is refused, and named`() {
    // The client learns that the address it named was never stored.
    val refused = unreadable("""{"redirect_uris":["https://app.example/cb"],"contacts":"a@b.example"}""")

    assertEquals(PodRegistrationError.INVALID_CLIENT_METADATA, refused.error)
    assertTrue("contacts" in refused.description, refused.description)
  }

  @Test
  fun `an address that is not a URI is refused as a redirect_uri`() {
    val refused = unreadable("""{"redirect_uris":[" "]}""")

    assertEquals(PodRegistrationError.INVALID_REDIRECT_URI, refused.error)
  }

  @Test
  fun `a client that named no address at all gets that far`() {
    // Empty and absent are the same statement, and both are the decision's to answer.
    assertEquals(emptySet(), metadata("""{"redirect_uris":[]}""").client.redirectUris)
    assertEquals(emptySet(), metadata("""{"client_name":"Nameless"}""").client.redirectUris)
  }

  @Test
  fun `values arrive trimmed, and a blank one as absent`() {
    // The fingerprint is computed from these, so a client that pads its name must keep the
    // identity it had before the padding.
    assertEquals("Notes", metadata(named("  Notes  ")).client.clientName)
    assertEquals(null, metadata(named("   ")).client.clientName)
  }

  @Test
  fun `a member this pod does not read survives in the body it stores`() {
    val read = metadata("""{"redirect_uris":["https://app.example/cb"],"scope":"openid offline_access"}""")

    assertEquals("openid offline_access", read.raw["scope"])
    assertEquals(listOf("https://app.example/cb"), read.raw["redirect_uris"])
  }

  private fun named(clientName: String) =
    """{"redirect_uris":["https://app.example/cb"],"client_name":"$clientName"}"""

  private fun metadata(body: String?) = assertIs<PodRegistrationRead.Metadata>(read(body))

  private fun unreadable(body: String?) = assertIs<PodRegistrationRead.Unreadable>(read(body)).refusal

  private fun read(body: String?) = PodRegistrationMessages.read(body)
}
