package org.sempods.controlplane

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.core.SempodsClientException
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.SempodsPod
import org.sempods.client.core.SempodsPodBase
import org.sempods.client.core.SempodsRequestAuth
import org.sempods.client.core.SempodsSession
import org.slf4j.event.Level

/**
 * Where the host credential may go, and where it may not.
 *
 * Host authority spans a host rather than a path under it, so the server root is the whole of the
 * boundary — and that is the boundary this checks: a request moved to another authority is refused
 * before it is written, and two sessions sharing one client never lend each other a credential.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsControlPlaneConfinementTest {

  private lateinit var server: ClientAndServer
  private lateinit var origin: String

  @BeforeAll
  fun startServer() {
    server = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    origin = "http://localhost:${server.port}"
  }

  @AfterAll
  fun stopServer() {
    server.stop()
  }

  @BeforeEach
  fun resetServer() {
    server.reset()
  }

  @Test
  fun `an interceptor that moves the request to another authority takes no admin credential along`() {
    // Same listener, another name for it: the check is on the authority the request names, not on the
    // address it would reach. A move within the host is not one — that is what host authority spans.
    val moving = Interceptor { chain ->
      chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.port}/_system/admin/pods/alice").build())
    }
    server.`when`(request()).respond(response().withStatusCode(204))

    closing(SempodsOkHttp.install(OkHttpClient.Builder().addInterceptor(moving)).build()) { http ->
      val refused = assertThrows<SempodsClientException> { adminOn(http).deletePod("alice") }
      assertTrue(refused.message!!.contains("not under this session's pod"), refused.message)
    }

    assertEquals(0, server.retrieveRecordedRequests(request()).size, "nothing left this client")
  }

  @Test
  fun `a redirect off the server is refused although the consumer turned redirects back on`() {
    server.`when`(request().withPath("/_system/admin/pods/alice"))
      .respond(
        response().withStatusCode(302)
          .withHeader("Location", "http://127.0.0.1:${server.port}/_system/admin/pods/alice"),
      )

    closing(SempodsOkHttp.install(OkHttpClient.Builder()).followRedirects(true).build()) { http ->
      val refused = assertThrows<SempodsClientException> { adminOn(http).deletePod("alice") }
      assertTrue(refused.message!!.contains("not under this session's pod"), refused.message)
    }
  }

  @Test
  fun `a pod call and a host call on one client carry only their own credential`() {
    server.`when`(request().withPath("/_system/admin/pods/alice")).respond(response().withStatusCode(204))
    server.`when`(request().withPath("/alice/_system/meta/date-modified"))
      .respond(response().withStatusCode(200).withBody("""{"dateModified":null}"""))

    closing(SempodsOkHttp.install(OkHttpClient.Builder()).build()) { http ->
      adminOn(http).deletePod("alice")

      val pod = SempodsPod(
        SempodsSession(SempodsPodBase.of("$origin/alice"), SempodsRequestAuth.bearer(POD_TOKEN)),
        http,
      )
      pod.metadata().exists()
    }

    val admin = server.retrieveRecordedRequests(request().withPath("/_system/admin/pods/alice")).single()
    val data = server.retrieveRecordedRequests(request().withPath("/alice/_system/meta/date-modified")).single()

    assertEquals("Bearer $ADMIN_SECRET", admin.getFirstHeader("Authorization"))
    assertEquals("Bearer $POD_TOKEN", data.getFirstHeader("Authorization"))
  }

  @Test
  fun `the host credential is whatever the consumer's mechanism sets`() {
    server.`when`(request()).respond(response().withStatusCode(204))

    val apiKey = SempodsRequestAuth.apiKeyHeader("X-Admin-Key", "k-1")
    val bearerPlusHeader = SempodsRequestAuth.bearer(ADMIN_SECRET)
      .andThen { request, _ -> request.header("X-Tenant", "acme") }

    closing(SempodsOkHttp.install(OkHttpClient.Builder()).build()) { http ->
      admin(apiKey, http).deletePod("alice")
      admin(bearerPlusHeader, http).deletePod("bob")
    }

    val keyed = server.retrieveRecordedRequests(request().withPath("/_system/admin/pods/alice")).single()
    assertEquals("k-1", keyed.getFirstHeader("X-Admin-Key"))
    assertNull(keyed.getFirstHeader("Authorization").takeIf { it.isNotEmpty() })

    val tenanted = server.retrieveRecordedRequests(request().withPath("/_system/admin/pods/bob")).single()
    assertEquals("Bearer $ADMIN_SECRET", tenanted.getFirstHeader("Authorization"))
    assertEquals("acme", tenanted.getFirstHeader("X-Tenant"))
  }

  private fun adminOn(http: OkHttpClient) = admin(SempodsRequestAuth.bearer(ADMIN_SECRET), http)

  private fun admin(auth: SempodsRequestAuth, http: OkHttpClient) =
    SempodsControlPlaneClient(SempodsSession(SempodsPodBase.of(origin), auth), http)

  private inline fun <T> closing(http: OkHttpClient, block: (OkHttpClient) -> T): T =
    try {
      block(http)
    } finally {
      http.dispatcher.executorService.shutdown()
      http.connectionPool.evictAll()
    }

  private companion object {

    const val ADMIN_SECRET = "sc_test-admin-secret"

    const val POD_TOKEN = "pod-bearer"
  }
}
