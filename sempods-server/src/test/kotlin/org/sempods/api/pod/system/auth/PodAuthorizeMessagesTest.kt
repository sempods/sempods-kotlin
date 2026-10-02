package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MultivaluedHashMap
import org.sempods.pods.oauth.flows.PodAuthorizeRequest
import org.sempods.pods.oauth.flows.PodAuthorizeTerms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure unit — what [PodAuthorizeMessages] hands the flow, with no server and no store.
 *
 * The HTTP suites pin the answers end to end; these cases pin which values pass on as sent and
 * which as the SDK read them, which an answer alone does not show.
 */
class PodAuthorizeMessagesTest {

  private fun query(vararg params: Pair<String, String>) =
    MultivaluedHashMap<String, String>().apply { params.forEach { (name, value) -> add(name, value) } }

  private val challenge = DelegatedAccessFlow.CODE_CHALLENGE

  private val base = arrayOf(
    "response_type" to "code",
    "client_id" to "dyn:abc",
    "redirect_uri" to "http://localhost:5173/callback",
    "code_challenge" to challenge,
    "code_challenge_method" to "S256",
  )

  @Test
  fun `the address, state and challenge pass on as sent, the rest as the SDK read it`() {
    val read = PodAuthorizeMessages.read(
      query(
        "response_type" to " code ",
        "client_id" to " dyn:abc ",
        "redirect_uri" to " http://localhost:5173/callback ",
        "state" to " s ",
        "code_challenge" to " $challenge",
        "code_challenge_method" to " S256 ",
        "prompt" to "consent  login",
        "scope" to " public-read  offline_access ",
      ),
    )

    assertEquals(
      PodAuthorizeRequest(
        clientId = " dyn:abc ",
        redirectUri = " http://localhost:5173/callback ",
        state = " s ",
        terms = PodAuthorizeTerms.Read(
          responseType = setOf("code"),
          codeChallenge = " $challenge",
          codeChallengeMethod = "S256",
          prompt = setOf("consent", "login"),
          scopes = setOf("public-read", "offline_access"),
        ),
      ),
      read,
    )
  }

  @Test
  fun `a parameter the route does not read is not parsed, however it is written`() {
    val read = PodAuthorizeMessages.read(
      query(*base, "request_uri" to "not a uri", "resource" to "relative", "resource" to "x", "response_mode" to "form_post"),
    )

    assertIs<PodAuthorizeTerms.Read>(read.terms)
    assertTrue(read.repeated.isEmpty(), "${read.repeated}")
  }

  @Test
  fun `a repeated parameter is named for the flow to refuse, and its first value read`() {
    val read = PodAuthorizeMessages.read(query(*base, "scope" to "public-read", "scope" to "offline_access", "state" to "a", "state" to "b"))

    assertEquals(sortedSetOf("scope", "state"), read.repeated)
    assertEquals(setOf("public-read"), assertIs<PodAuthorizeTerms.Read>(read.terms).scopes)
    assertEquals("a", read.state)
  }

  @Test
  fun `what the grammar does not allow is malformed`() {
    val cases = mapOf(
      "no response_type" to base.filter { it.first != "response_type" },
      "an unknown prompt" to base.toList() + ("prompt" to "consent foo"),
      "none with another prompt" to base.toList() + ("prompt" to "none login"),
    )
    for ((case, params) in cases) {
      val terms = PodAuthorizeMessages.read(query(*params.toTypedArray())).terms
      assertIs<PodAuthorizeTerms.Malformed>(terms, case)
    }
    // A response_type the grammar allows is read, whether or not the pod offers it: that is the flow's.
    val implicit = PodAuthorizeMessages.read(query(*base.filter { it.first != "response_type" }.toTypedArray(), "response_type" to "code token"))
    assertEquals(setOf("code", "token"), assertIs<PodAuthorizeTerms.Read>(implicit.terms).responseType)
  }
}
