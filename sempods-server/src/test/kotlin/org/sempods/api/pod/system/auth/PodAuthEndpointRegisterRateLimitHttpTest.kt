package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the address budgets at `/register` are reached on a real connector, and what a caller who
 * has spent one is told. The arithmetic is `PodRegistrationRateLimiterTest`'s; the installer
 * budget is covered beside the installation cases in `PodAuthEndpointHttpTest`.
 *
 * Every case brings its own address, as in `PodAuthEndpointRateLimitHttpTest` and for the same
 * reason: the limiter is a JVM-wide singleton and these classes run concurrently.
 */
class PodAuthEndpointRegisterRateLimitHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  private val publicBudget = SempodsModule.config.registerRateLimitPublicBurst
  private val protectedBudget = SempodsModule.config.registerRateLimitProtectedBurst

  private fun registerUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}$podName/_system/auth/register"

  private fun freshAddress(): String = "198.51.100.4, ${randomId()}"

  private fun register(
    podName: String,
    address: String?,
    body: String = """{"redirect_uris":["http://localhost:5173/callback"],"client_name":"Budgeted"}""",
    bearer: String? = null,
  ): TestHttpResponse = http.preparePost(registerUrl(podName))
    .addHeader("Content-Type", "application/json")
    .apply {
      address?.let { addHeader("X-Forwarded-For", it) }
      bearer?.let { addHeader("Authorization", "Bearer $it") }
    }
    .setBody(body)
    .execute()

  private fun clientIdOf(response: TestHttpResponse): String =
    JsonMappers.default().readValue(response.responseBody, Map::class.java)["client_id"] as String

  @Test
  fun `a public caller past its budget is refused with 429, Retry-After and slow_down`() {
    assertTrue(publicBudget > 0, "the suite's environment must enable the budget for this to mean anything")
    val pod = sempodsTestFactory.newPod()
    val address = freshAddress()
    // The same metadata every time: a reconnecting client. It is answered with the client it
    // already has, and still counted.
    val first = register(pod.name, address)
    assertEquals(201, first.statusCode, first.responseBody)
    repeat(publicBudget - 1) {
      val again = register(pod.name, address)
      assertEquals(201, again.statusCode, again.responseBody)
      assertEquals(clientIdOf(first), clientIdOf(again))
    }

    val refused = register(pod.name, address)

    assertEquals(429, refused.statusCode, refused.responseBody)
    assertEquals("60", refused.getHeader("Retry-After"))
    assertEquals("no-store", refused.getHeader("Cache-Control"))
    assertTrue(refused.contentType.orEmpty().startsWith("application/json"), refused.contentType)
    assertTrue("slow_down" in refused.responseBody, refused.responseBody)
  }

  @Test
  fun `the public budget follows the address, not the pod`() {
    val first = sempodsTestFactory.newPod()
    val second = sempodsTestFactory.newPod()
    val address = freshAddress()
    repeat(publicBudget) { assertEquals(201, register(first.name, address).statusCode) }

    assertEquals(429, register(second.name, address).statusCode)
  }

  @Test
  fun `a spent public budget leaves the protected one of the same address, and the other way round`() {
    val pod = sempodsTestFactory.newPod()
    val publicSpent = freshAddress()
    repeat(publicBudget) { register(pod.name, publicSpent) }
    assertEquals(429, register(pod.name, publicSpent).statusCode)
    // An unverifiable bearer is answered on its merits, which is a 401 and not a 429.
    assertEquals(401, register(pod.name, publicSpent, bearer = "not-a-token").statusCode)

    val protectedSpent = freshAddress()
    repeat(protectedBudget) { assertEquals(401, register(pod.name, protectedSpent, bearer = "not-a-token").statusCode) }
    assertEquals(429, register(pod.name, protectedSpent, bearer = "not-a-token").statusCode)
    assertEquals(201, register(pod.name, protectedSpent).statusCode)
  }

  @Test
  fun `a request carrying no forwarded-for header is not limited by address`() {
    val pod = sempodsTestFactory.newPod()
    repeat(publicBudget + 3) {
      val response = register(pod.name, address = null)
      assertEquals(201, response.statusCode, response.responseBody)
    }
  }
}
