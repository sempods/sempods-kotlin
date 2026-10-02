package org.sempods.mcp.pods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PodAuthorizationAnswerTest {

  private val pod = "https://pods.example/alice"

  private fun metadata(issuer: String = pod, issAdvertised: Boolean = false) = PodOAuthMetadata(
    issuer = issuer,
    authorizationEndpoint = "$pod/_system/auth/authorize",
    tokenEndpoint = "$pod/_system/auth/token",
    registrationEndpoint = null,
    jwksUri = null,
    authorizationResponseIssParameterSupported = issAdvertised,
  )

  private fun enc(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8)

  @Test
  fun `the pod's own issuer lets its answer through, in either spelling`() {
    for (iss in listOf(pod, "$pod/")) {
      assertEquals(
        PodAuthorizationAnswer.Code("c1"),
        readPodAuthorizationAnswer("code=c1&state=s&iss=${enc(iss)}", metadata(issAdvertised = true)),
        iss,
      )
    }
    assertEquals(
      PodAuthorizationAnswer.Refused("access_denied"),
      readPodAuthorizationAnswer("error=access_denied&state=s&iss=${enc(pod)}", metadata(issAdvertised = true)),
    )
  }

  @Test
  fun `another authorization server's answer is refused, a refusal included`() {
    // RFC 9207 §2.4: checked before anything in the answer is used, and an error is no exception —
    // a foreign `access_denied` would otherwise end this connect on another server's word.
    for (query in listOf("code=c1&state=s", "error=access_denied&state=s")) {
      for (iss in listOf("https://pods.example/bob", "$pod/_system/auth")) {
        assertIs<PodAuthorizationAnswer.NotFromPod>(
          readPodAuthorizationAnswer("$query&iss=${enc(iss)}", metadata()),
          "$query from $iss",
        )
      }
    }
  }

  @Test
  fun `a missing issuer is refused exactly where the pod promised one`() {
    assertIs<PodAuthorizationAnswer.NotFromPod>(readPodAuthorizationAnswer("code=c1&state=s", metadata(issAdvertised = true)))
    assertEquals(PodAuthorizationAnswer.Code("c1"), readPodAuthorizationAnswer("code=c1&state=s", metadata()))
  }

  @Test
  fun `a pod still on its old issuer is held to that one`() {
    // A pod server without the issuer switch of #193 names `{pod}/_system/auth` in its metadata.
    val legacy = metadata(issuer = "$pod/_system/auth")

    assertEquals(PodAuthorizationAnswer.Code("c1"), readPodAuthorizationAnswer("code=c1&iss=${enc("$pod/_system/auth")}", legacy))
    assertIs<PodAuthorizationAnswer.NotFromPod>(readPodAuthorizationAnswer("code=c1&iss=${enc(pod)}", legacy))
  }

  @Test
  fun `a member sent twice is no single answer`() {
    for (query in listOf("code=c1&iss=${enc(pod)}&iss=${enc("https://evil.example")}", "code=c1&code=c2", "error=a&error=b")) {
      assertIs<PodAuthorizationAnswer.NotFromPod>(readPodAuthorizationAnswer(query, metadata()), query)
    }
    // The registered address's own parameters are the client's business, repeated or not.
    assertEquals(PodAuthorizationAnswer.Code("c1"), readPodAuthorizationAnswer("next=a&next=b&code=c1", metadata()))
  }

  @Test
  fun `an answer with neither a code nor an error is nothing to act on`() {
    assertEquals(PodAuthorizationAnswer.Malformed, readPodAuthorizationAnswer("state=s", metadata()))
    assertEquals(PodAuthorizationAnswer.Malformed, readPodAuthorizationAnswer(null, metadata()))
  }
}
