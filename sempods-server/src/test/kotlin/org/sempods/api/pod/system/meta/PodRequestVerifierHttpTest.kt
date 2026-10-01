package org.sempods.api.pod.system.meta

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.api.assertPodBearerChallenge
import org.sempods.commons.guice.GuiceAppTestProxy
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.pods.oauth.spi.PodRequestVerifier
import org.sempods.pods.oauth.spi.PodResourceRequest
import org.sempods.pods.oauth.spi.PodTokenAuthentication
import org.sempods.pods.oauth.spi.PodTokenRejection
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * The resource routes depend on the [PodRequestVerifier] boundary and on nothing behind it: another
 * implementation, wired without a change to an endpoint or to the grant policy, decides who is
 * calling. This proves the boundary — it is not a production replacement for the engine.
 */
class PodRequestVerifierHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  @Inject
  private lateinit var verifier: GuiceAppTestProxy<PodRequestVerifier>

  private fun url(podName: String) = "${SempodsModule.config.apiBaseUrl}$podName/_system/meta/date-modified"

  /** Runs [block] with [answer] deciding every request it makes. */
  private fun <R> verifyingWith(answer: PodRequestVerifier, block: () -> R): R =
    verifier.observe(delegate = answer, useDelegateResult = true) { block() }

  @Test
  fun `a verifier that answers no token makes the caller anonymous whatever the bearer says`() {
    val pod = sempodsTestFactory.newPod()

    // The real verifier turns this bearer into a 401; the alternative decides otherwise.
    val response = verifyingWith({ _, _ -> PodTokenAuthentication.NoToken }) {
      http.prepareGet(url(pod.name)).addHeader("Authorization", "Bearer not-a-real-jwt").execute()
    }

    assertEquals(200, response.statusCode, response.responseBody)
  }

  @Test
  fun `a verifier that rejects is answered with the pod's bearer challenge`() {
    val pod = sempodsTestFactory.newPod()

    val response = verifyingWith({ _, _ -> PodTokenAuthentication.Rejected(PodTokenRejection.invalidToken) }) {
      http.prepareGet(url(pod.name)).execute()
    }

    assertPodBearerChallenge(response, pod.name)
  }

  @Test
  fun `the verifier is handed the method, the externally known target and the headers`() {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<PodResourceRequest>()

    verifyingWith({ request, _ -> seen += request; PodTokenAuthentication.NoToken }) {
      http.prepareGet(url(pod.name)).addHeader("Authorization", "Bearer abc").execute()
    }

    val request = seen.single()
    assertEquals("GET", request.method)
    assertEquals(url(pod.name), request.target.toString())
    assertEquals(listOf("Bearer abc"), request.header("authorization"))
  }
}
