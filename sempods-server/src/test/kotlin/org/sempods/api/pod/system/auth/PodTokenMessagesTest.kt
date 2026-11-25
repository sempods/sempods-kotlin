package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MultivaluedHashMap
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.pods.oauth.flows.PodTokenResult
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure unit — what [PodTokenMessages] hands the exchange, with no server and no store.
 *
 * The HTTP suites pin the answers end to end; these cases pin the projection between the SDK's
 * parsers and the exchange, which an answer alone does not show.
 */
class PodTokenMessagesTest {

  private fun form(vararg params: Pair<String, String>) =
    MultivaluedHashMap<String, String>().apply { params.forEach { (name, value) -> add(name, value) } }

  private fun basic(credentials: String) = "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())

  private val verifier = DelegatedAccessFlow.CODE_VERIFIER

  @Test
  fun `a code exchange passes on the form's values, trimmed except the verifier`() {
    val read = PodTokenMessages.read(
      form(
        "grant_type" to "authorization_code",
        "code" to " c ",
        "redirect_uri" to " http://localhost:5173/callback ",
        "client_id" to " dyn:abc ",
        "code_verifier" to verifier,
      ),
      authorization = basic("someone:else"),
    )

    // The header names nobody here: a code exchange is a public client's.
    assertEquals(PodTokenRead.AuthorizationCode("c", "http://localhost:5173/callback", "dyn:abc", verifier), read)
  }

  @Test
  fun `a parameter the route does not read may repeat, and is not read`() {
    val read = PodTokenMessages.read(
      form(
        "grant_type" to "refresh_token",
        "refresh_token" to "rt",
        "client_id" to "dyn:abc",
        "resource" to "not a uri",
        "resource" to "http://example.org/#fragment",
        "client_secret" to "s",
        "client_secret" to "s",
      ),
      authorization = null,
    )

    assertEquals(PodTokenRead.Refresh("rt", "dyn:abc", scope = null), read)
  }

  @Test
  fun `refresh and client_credentials pass scope on as text`() {
    assertEquals(
      PodTokenRead.Refresh("rt", "dyn:abc", "public-read profile"),
      PodTokenMessages.read(
        form("grant_type" to "refresh_token", "refresh_token" to "rt", "client_id" to "dyn:abc", "scope" to " public-read profile "),
        authorization = null,
      ),
    )
    assertEquals(
      PodTokenRead.ClientCredentials("svc:a", "s", "x"),
      PodTokenMessages.read(form("grant_type" to "client_credentials", "scope" to "x"), basic("svc%3Aa:s")),
    )
  }

  @Test
  fun `the budget is keyed by the client the exchange then authenticates`() {
    for (header in listOf(basic("svc%3Aa:s"), basic(" svc%3Aa :s"), "basic " + Base64.getEncoder().encodeToString("svc%3Aa:s".toByteArray()))) {
      val read = PodTokenMessages.read(form("grant_type" to "client_credentials"), header)
      assertEquals("svc:a", (read as PodTokenRead.ClientCredentials).clientId, header)
      assertEquals(read.clientId, PodTokenMessages.basicClientId(header), header)
    }
    // A blank client id fails the SDK's identifier rather than its parser.
    assertNull(PodTokenMessages.basicClientId(basic(":s")))
    assertEquals(
      PodTokenRead.Refused(PodTokenResult.ClientAuthenticationRequired("HTTP Basic authentication or a client assertion required")),
      PodTokenMessages.read(form("grant_type" to "client_credentials"), basic(":s")),
    )
  }

  @Test
  fun `a client assertion is read with the client it names`() {
    val assertion = unsignedJwt(subject = "did:web:console.example")
    val read = PodTokenMessages.read(
      form("grant_type" to "client_credentials", "client_assertion_type" to JWT_BEARER, "client_assertion" to assertion),
      authorization = null,
    )

    assertEquals(PodTokenRead.ClientAssertion("did:web:console.example", assertion, null), read)
    assertEquals("did:web:console.example", PodTokenMessages.clientCredentialsClientId(null, assertion), "the budget's key")
    assertEquals(
      PodTokenRead.ClientAssertion("did:web:other.example", assertion, null),
      PodTokenMessages.read(
        form(
          "grant_type" to "client_credentials", "client_id" to "did:web:other.example",
          "client_assertion_type" to JWT_BEARER, "client_assertion" to assertion,
        ),
        authorization = null,
      ),
      "a client_id beside it is what the verifier holds the assertion to",
    )
  }

  @Test
  fun `a client assertion beside Basic, or half of one, is refused`() {
    val assertion = unsignedJwt(subject = "did:web:console.example")
    fun code(vararg fields: Pair<String, String>, authorization: String? = null): OAuthErrorCode =
      ((PodTokenMessages.read(form("grant_type" to "client_credentials", *fields), authorization) as PodTokenRead.Refused)
        .result as PodTokenResult.Refused).code

    assertEquals(
      OAuthErrorCode.INVALID_REQUEST,
      code("client_assertion_type" to JWT_BEARER, "client_assertion" to assertion, authorization = basic("svc%3Aa:s")),
    )
    assertEquals(OAuthErrorCode.INVALID_REQUEST, code("client_assertion" to assertion))
    assertEquals(OAuthErrorCode.INVALID_REQUEST, code("client_assertion_type" to JWT_BEARER))
    assertEquals(OAuthErrorCode.INVALID_CLIENT, code("client_assertion_type" to "urn:example:saml", "client_assertion" to assertion))
    assertEquals(OAuthErrorCode.INVALID_CLIENT, code("client_assertion_type" to JWT_BEARER, "client_assertion" to "not-a-jwt"))
  }

  /** A JWT with [subject], signed with nothing: this layer reads its shape, never its signature. */
  private fun unsignedJwt(subject: String): String {
    fun part(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    return "${part("""{"alg":"ES256","kid":"k"}""")}.${part("""{"iss":"$subject","sub":"$subject"}""")}.${part("sig")}"
  }

  @Test
  fun `a grant the SDK knows but the pod does not offer is unsupported, not malformed`() {
    // `password` without `username` would be `invalid_request` from the SDK's own parser.
    val read = PodTokenMessages.read(form("grant_type" to "password"), authorization = null)

    assertEquals(OAuthErrorCode.UNSUPPORTED_GRANT_TYPE, ((read as PodTokenRead.Refused).result as PodTokenResult.Refused).code)
  }

  private companion object {
    const val JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
  }
}
