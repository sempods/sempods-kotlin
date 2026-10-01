package org.sempods.api.pod.system.ai.semweb

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who reaches the AI routes, `{pod}/_system/ai/semweb/{text2model,model2model}`. They take an app
 * token of this pod and nothing else, so each case stops before the provider is called: a token that
 * gets through is answered on its (empty) body.
 */
class PodAiSemWebEndpointHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  private val routes = listOf("text2model", "model2model")

  private fun post(pod: String, route: String, authorization: String?): TestHttpResponse =
    http.preparePost("${SempodsModule.config.apiBaseUrl}$pod/_system/ai/semweb/$route")
      .addHeader("Content-Type", "application/json")
      .apply { authorization?.let { addHeader("Authorization", it) } }
      .setBody("{}")
      .execute()

  @Test
  fun `an app token of the pod gets through, and the request is judged on its body`() {
    val pod = sempodsTestFactory.newPod()
    val token = mintScopedToken(pod.name, listOf("${sempodsTestFactory.publicContextUri(pod.name)}#read"))

    for (route in routes) {
      val answer = post(pod.name, route, "Bearer $token")
      assertEquals(400, answer.statusCode, "$route: ${answer.responseBody}")
      assertTrue("missing_" in answer.responseBody, "$route: ${answer.responseBody}")
    }
  }

  @Test
  fun `no bearer or a rejected one is answered with the pod's bearer challenge`() {
    val pod = sempodsTestFactory.newPod()

    for (route in routes) {
      for (authorization in listOf(null, "Bearer not-a-real-jwt")) {
        assertPodBearerChallenge(post(pod.name, route, authorization), pod.name)
      }
    }
  }

  @Test
  fun `a token another pod issued is forbidden`() {
    val pod = sempodsTestFactory.newPod()
    val other = sempodsTestFactory.newPod()
    val foreign = mintScopedToken(other.name, listOf("${sempodsTestFactory.publicContextUri(other.name)}#read"))

    for (route in routes) {
      val answer = post(pod.name, route, "Bearer $foreign")
      assertEquals(403, answer.statusCode, "$route: ${answer.responseBody}")
      assertTrue(pod.name in answer.responseBody, "$route: ${answer.responseBody}")
    }
  }

  @Test
  fun `an authority for one named operation does not reach the routes`() {
    val pod = sempodsTestFactory.newPod()
    val manager = mintContextsManagerToken(pod.name, webId = pod.owner)

    for (route in routes) {
      val answer = post(pod.name, route, "Bearer $manager")
      assertEquals(403, answer.statusCode, "$route: ${answer.responseBody}")
      assertTrue("does not authorize this route" in answer.responseBody, "$route: ${answer.responseBody}")
    }
  }
}
