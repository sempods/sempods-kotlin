package org.sempods.api.pod.system.meta

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The routes below `{pod}/…` take their caller from the
 * [org.sempods.pods.authentication.spi.PodRequestAuthenticator] they are bound with, and from
 * nothing behind it: another implementation decides who is calling, which pod is there and what a
 * refusal says, with no change to a route. This proves the seam — it is not a production
 * replacement for this server's bearer.
 */
class PodRequestAuthenticatorHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  private fun url(podName: String) = "${SempodsModule.config.apiBaseUrl}$podName/_system/meta/date-modified"

  @Test
  fun `a route hands the authenticator the pod and the request as the client sent it`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<Pair<String, PodApiRequest>>()
    requestAuthenticator.answerWith { name, request ->
      seen += name to request
      requestAuthenticator.real.authenticate(name, request)
    }

    val response = http.prepareGet("${url(pod.name)}?x=a%26b").addHeader("X-Session", "abc").execute()

    assertEquals(200, response.statusCode, response.responseBody)
    val (name, request) = seen.single()
    assertEquals(pod.name, name)
    assertEquals("GET", request.method)
    assertEquals("${pod.name}/_system/meta/date-modified", request.path)
    assertEquals("x=a%26b", request.query)
    assertEquals(listOf("abc"), request.headers.entries.single { it.key.equals("X-Session", ignoreCase = true) }.value)
  }

  @Test
  fun `a pod the authenticator does not find is a 404, whatever the registry holds`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { _, _ -> PodRequestAuthentication.PodNotFound }

    assertEquals(404, http.prepareGet(url(pod.name)).execute().statusCode)
  }

  @Test
  fun `an unauthenticated caller is a 401 carrying the authenticator's own challenge`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { name, _ -> PodRequestAuthentication.Unauthenticated("Session realm=\"$name\"") }

    val response = http.prepareGet(url(pod.name)).execute()

    assertEquals(401, response.statusCode, response.responseBody)
    assertEquals("Session realm=\"${pod.name}\"", response.getHeader("WWW-Authenticate"))
  }

  @Test
  fun `an unauthenticated caller without a challenge is a 401 without one`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { _, _ -> PodRequestAuthentication.Unauthenticated(challenge = null) }

    val response = http.prepareGet(url(pod.name)).execute()

    assertEquals(401, response.statusCode, response.responseBody)
    assertNull(response.getHeader("WWW-Authenticate"))
  }

  @Test
  fun `a caller identified without an OAuth client has its find results kept out of shared caches`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { name, request ->
      val anonymous = assertIs<PodRequestAuthentication.Authenticated>(requestAuthenticator.real.authenticate(name, request))
      PodRequestAuthentication.Authenticated(
        anonymous.credentials.copy(authenticated = true, tokenSub = "https://id.test/someone"),
      )
    }

    val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/find?text=harbour").execute()

    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("private, no-store", response.getHeader("Cache-Control"))
  }
}
