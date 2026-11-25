package org.sempods.api.pod.system.meta

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.authentication.spi.PodRequestAuthenticator
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
  fun `a literal semicolon reaches the authenticator as the client sent it`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    val seen = CopyOnWriteArrayList<PodApiRequest>()
    requestAuthenticator.answerWith { name, request ->
      seen += request
      requestAuthenticator.real.authenticate(name, request)
    }

    http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/notes/a;b").execute()

    assertEquals("${pod.name}/notes/a;b", seen.single().path)
  }

  @Test
  fun `a pod the authenticator does not find is a 404 no cache keeps, whatever the registry holds`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { _, _ -> PodRequestAuthentication.PodNotFound }

    val response = http.prepareGet(url(pod.name)).execute()

    assertEquals(404, response.statusCode)
    assertEquals("no-store", response.getHeader("Cache-Control"))
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
  fun `a refusal without a challenge cannot be written`() {
    assertFailsWith<IllegalArgumentException> { PodRequestAuthentication.Unauthenticated(" ") }
  }

  @Test
  fun `credentials an authorizer copies from an anonymous template with a client id count as authenticated`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { name, request ->
      val anonymous = assertIs<PodRequestAuthentication.Authenticated>(requestAuthenticator.real.authenticate(name, request))
      PodRequestAuthentication.Authenticated(anonymous.credentials.copy(oauthClientId = "dyn:an-app"))
    }

    val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/find?text=harbour").execute()

    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("private, no-store", response.getHeader("Cache-Control"))
  }

  @Test
  fun `a caller identified without an OAuth client has its find results kept out of shared caches`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith { name, request ->
      val anonymous = assertIs<PodRequestAuthentication.Authenticated>(requestAuthenticator.real.authenticate(name, request))
      PodRequestAuthentication.Authenticated(
        anonymous.credentials.copy(identified = true, tokenSub = "https://id.test/someone"),
      )
    }

    val response = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/find?text=harbour").execute()

    assertEquals(200, response.statusCode, response.responseBody)
    assertEquals("private, no-store", response.getHeader("Cache-Control"))
  }

  @Test
  fun `a caller-dependent answer varies on every header the authenticator reads credentials from`() = withSetup {
    val pod = sempodsTestFactory.newPod()
    requestAuthenticator.answerWith(object : PodRequestAuthenticator {
      override fun authenticate(pod: String, request: PodApiRequest) = requestAuthenticator.real.authenticate(pod, request)
      override val credentialHeaders = setOf("Authorization", "Cookie")
    })

    val find = http.prepareGet("${SempodsModule.config.apiBaseUrl}${pod.name}/_system/find?text=harbour").execute()
    val dateModified = http.prepareGet(url(pod.name)).execute()

    listOf(find, dateModified).forEach { response ->
      assertEquals(200, response.statusCode, response.responseBody)
      // Jetty's gzip handler adds Accept-Encoding of its own.
      val vary = response.headers.values("Vary").flatMap { it.split(",") }.map { it.trim() }.toSet()
      assertTrue(vary.containsAll(setOf("Accept", "Authorization", "Cookie")), "Vary was $vary")
    }
  }
}
