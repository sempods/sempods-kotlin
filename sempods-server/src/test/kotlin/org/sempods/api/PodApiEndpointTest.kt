package org.sempods.api

import com.google.inject.AbstractModule
import com.google.inject.Guice
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import org.junit.jupiter.api.Test
import org.sempods.pods.authentication.spi.PodApiRequest
import org.sempods.pods.authentication.spi.PodRequestAuthentication
import org.sempods.pods.authentication.spi.PodRequestAuthenticator
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.spec.PodRef
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * [PodApiEndpoint] asks the [PodRequestAuthenticator] it is bound with and nothing else: no pod
 * registry, no store and no configuration are in this injector.
 */
class PodApiEndpointTest {

  private val pod = PodRef(uri = URI("https://pods.example/notes"), name = "notes", owner = "https://id.example/me")
  private val request = PodApiRequest(
    method = "GET",
    path = "notes/_system/find",
    query = "text=harbour",
    headers = mapOf("Authorization" to listOf("Session abc")),
  )

  /** A route below `{pod}/…`, as a deployment would write one. */
  internal class Route : PodApiEndpoint() {
    fun caller(pod: String, request: PodApiRequest): SempodsCredentials = authenticate(pod, request)
  }

  private fun routeAnswering(answer: (String, PodApiRequest) -> PodRequestAuthentication): Route {
    val injector = Guice.createInjector(object : AbstractModule() {
      override fun configure() {
        bind(PodRequestAuthenticator::class.java).toInstance(PodRequestAuthenticator { pod, request -> answer(pod, request) })
      }
    })
    return injector.getInstance(Route::class.java)
  }

  @Test
  fun `the bound authenticator is asked with the pod and the request, and its credentials are the caller`() {
    val credentials = SempodsCredentials(pod = pod, restrictedContexts = emptySet(), authenticated = true)
    val seen = CopyOnWriteArrayList<Pair<String, PodApiRequest>>()
    val route = routeAnswering { name, request ->
      seen += name to request
      PodRequestAuthentication.Authenticated(credentials)
    }

    assertSame(credentials, route.caller("notes", request))
    assertEquals(listOf("notes" to request), seen)
  }

  @Test
  fun `a pod the authenticator does not find is a 404`() {
    val route = routeAnswering { _, _ -> PodRequestAuthentication.PodNotFound }

    val refused = assertFailsWith<WebApplicationException> { route.caller("notes", request) }

    assertEquals(404, refused.response.status)
  }

  @Test
  fun `an unauthenticated caller is a 401 carrying the authenticator's challenge`() {
    val route = routeAnswering { _, _ -> PodRequestAuthentication.Unauthenticated("Session realm=\"notes\"") }

    val refused = assertFailsWith<InvalidBearerException> { route.caller("notes", request) }

    assertEquals(401, refused.response.status)
    assertEquals("notes", refused.podName)
    assertEquals("Session realm=\"notes\"", refused.response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE))
  }

  @Test
  fun `an unauthenticated caller without a challenge is a 401 without one`() {
    val route = routeAnswering { _, _ -> PodRequestAuthentication.Unauthenticated(challenge = null) }

    val refused = assertFailsWith<InvalidBearerException> { route.caller("notes", request) }

    assertEquals(401, refused.response.status)
    assertNull(refused.response.getHeaderString(HttpHeaders.WWW_AUTHENTICATE))
  }

  @Test
  fun `credentials without a client id count as authenticated when the authenticator says so`() {
    assertEquals(false, SempodsCredentials(pod = pod, restrictedContexts = emptySet()).authenticated)
    assertEquals(true, SempodsCredentials(pod = pod, restrictedContexts = emptySet(), oauthClientId = "app").authenticated)
    assertEquals(true, SempodsCredentials(pod = pod, restrictedContexts = emptySet(), authenticated = true).authenticated)
  }
}
