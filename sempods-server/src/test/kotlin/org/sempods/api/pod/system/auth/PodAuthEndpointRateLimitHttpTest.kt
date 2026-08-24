package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import org.junit.jupiter.api.Test
import org.sempods.SempodsIntegrationTest
import org.sempods.SempodsModule
import org.sempods.commons.okhttp.TestHttpClient
import org.sempods.commons.okhttp.TestHttpResponse
import org.sempods.commons.tests.TestUtil.randomId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the token endpoint's budget is reached on a real connector, and what a caller who has spent
 * it is told.
 *
 * The arithmetic is `PodTokenRateLimiterTest`'s subject; what only an HTTP-level case can show is
 * that the limiter is wired into the request path at all rather than merely constructed — the
 * failure mode a unit test cannot see. The budget comes from the environment the root build hands
 * this module's test JVM (`SEMPODS_TOKEN_RATE_LIMIT_PER_MINUTE`), which is off by default in
 * development.
 *
 * **Every case brings its own address.** The bucket is keyed by `X-Forwarded-For`, the injector and
 * its singletons live for the whole JVM, and these classes run concurrently — so a fresh address
 * per case is what makes each one repeatable on its own and beside its siblings. Nothing is
 * cleaned up afterwards, because nothing shared was touched.
 */
class PodAuthEndpointRateLimitHttpTest : SempodsIntegrationTest() {

  @Inject
  private lateinit var http: TestHttpClient

  /**
   * What a fresh key may spend before the first refusal — the burst, not the rate. Read from the
   * environment the root build gives this JVM so the test does not restate either number.
   */
  private val budget = SempodsModule.config.tokenRateLimitBurst

  private fun tokenUrl(podName: String): String =
    "${SempodsModule.config.apiBaseUrl}$podName/_system/auth/token"

  /**
   * An address no other case in this JVM uses, so this case owns its bucket.
   *
   * Two entries, with the unique one on the right: that is where a proxy appends what it saw, and
   * writing it this way means these cases would stop passing if the reading ever moved back to the
   * leftmost — the entry a caller writes for itself.
   */
  private fun freshAddress(): String = "198.51.100.4, ${randomId()}"

  private fun postRefresh(
    podName: String,
    address: String,
    clientId: String = "did:web:localhost%3A5173",
  ): TestHttpResponse =
    http.preparePost(tokenUrl(podName))
      .addHeader("Content-Type", "application/x-www-form-urlencoded")
      .addHeader("X-Forwarded-For", address)
      .setBody("grant_type=refresh_token&refresh_token=not-a-real-token&client_id=$clientId")
      .execute()

  @Test
  fun `a caller past its budget is refused with 429, Retry-After and slow_down`() {
    assertTrue(budget > 0, "the suite's environment must enable the budget for this to mean anything")
    val pod = sempodsTestFactory.newPod()
    val address = freshAddress()

    // Every one of these is refused as `invalid_grant` — an unrecognised refresh token, which is
    // exactly the branch the incident behind this limit hammered.
    repeat(budget) {
      assertEquals(400, postRefresh(pod.name, address).statusCode)
    }

    val refused = postRefresh(pod.name, address)
    assertEquals(429, refused.statusCode, "body=${refused.responseBody}")
    assertEquals("60", refused.getHeader("Retry-After"))
    assertEquals("no-store", refused.getHeader("Cache-Control"))
    assertTrue("slow_down" in refused.responseBody, refused.responseBody)
  }

  @Test
  fun `the budget follows the client, not the pod`() {
    // The multiplier this limit exists to catch: one client holding grants on several pods must
    // not get a fresh budget per pod, or each pod sees a fraction of the traffic it is causing.
    val first = sempodsTestFactory.newPod()
    val second = sempodsTestFactory.newPod()
    val address = freshAddress()

    repeat(budget) { assertEquals(400, postRefresh(first.name, address).statusCode) }

    assertEquals(429, postRefresh(second.name, address).statusCode)
  }

  @Test
  fun `a second client behind the same address keeps its own budget`() {
    val pod = sempodsTestFactory.newPod()
    val address = freshAddress()

    repeat(budget) { assertEquals(400, postRefresh(pod.name, address).statusCode) }
    assertEquals(429, postRefresh(pod.name, address).statusCode)

    // A different app behind the same NAT is a different bucket — the reason the key is a pair.
    assertEquals(400, postRefresh(pod.name, address, clientId = "dyn:${randomId()}").statusCode)
  }

  @Test
  fun `a request carrying no forwarded-for header is not limited`() {
    // Nothing in front of the server means no way to tell one caller from another, so the endpoint
    // answers on its merits rather than sharing one bucket with everybody.
    val pod = sempodsTestFactory.newPod()
    repeat(budget + 3) {
      val response = http.preparePost(tokenUrl(pod.name))
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .setBody("grant_type=refresh_token&refresh_token=not-a-real-token&client_id=dyn:${randomId()}")
        .execute()
      assertEquals(400, response.statusCode, "body=${response.responseBody}")
    }
  }
}
