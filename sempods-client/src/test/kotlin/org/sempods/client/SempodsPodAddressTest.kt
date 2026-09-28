package org.sempods.client

import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Dns
import okhttp3.Interceptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * A pod named on one host and reached at another: the name decides what an IRI addresses, the
 * address decides where the request and its credential go.
 */
class SempodsPodAddressTest : MockPodTest() {

  private val name = "https://acme.example/api/pod"

  private fun acme() =
    SempodsSession(SempodsPodBase.of(name).reachedAt("$origin/internal"), SempodsRequestAuth.bearer("token-a"))

  @Test
  fun `a context named on the public host is managed at the address`() {
    server.`when`(request().withMethod("DELETE")).respond(response().withStatusCode(204))

    sempodsClient().closing { client ->
      assertEquals(204, SempodsPod(acme(), client).contexts().delete("$name/_system/contexts/spaces/default").status)
    }

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("/internal/_system/contexts/spaces/default", sent.path.value)
    assertEquals("Bearer token-a", sent.getFirstHeader("Authorization"))
  }

  @Test
  fun `a resource is addressed by the name and read at the address`() {
    server.`when`(request().withMethod("GET")).respond(response().withStatusCode(200).withBody("{}"))

    sempodsClient().closing { client ->
      val pod = SempodsPod(acme(), client)
      val read = pod.resources().getText("$name/events/1")

      assertEquals("$origin/internal/events/1", read.url)
      val atAddress = assertThrows<IllegalArgumentException> { pod.resources().getText("$origin/internal/events/1") }
      assertTrue(atAddress.message!!.contains("is not under the pod '$name'"), atAddress.message)
    }
    assertEquals("/internal/events/1", server.retrieveRecordedRequests(request()).single().path.value)
  }

  @Test
  fun `a browser is sent to the name`() {
    sempodsClient().closing { client ->
      val url = SempodsPodAuthorization(acme(), client)
        .authorizationUrl("dyn:abc", "http://127.0.0.1:4711/cb", "service-clients:install", "s1", SempodsPkce.generate())
      assertEquals("$name/_system/auth/authorize", url.newBuilder().query(null).build().toString())
    }
  }

  @Test
  fun `a request moved to the name carries no credential there`() {
    // Both on this server, so the move is reachable and only confinement stands in its way.
    val session = SempodsSession(
      SempodsPodBase.of("http://127.0.0.1:${server.port}/api/pod").reachedAt("$origin/internal"),
      SempodsRequestAuth.bearer("token-a"),
    )
    val moving = Interceptor { chain ->
      chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.port}/api/pod/x").build())
    }

    sempodsClient { addInterceptor(moving) }.closing { client ->
      val refused = assertThrows<SempodsClientException> { client.newCall(session.newRequest("GET", "x").build()).execute().close() }
      assertTrue(refused.message!!.contains("at '$origin/internal'"), refused.message)
    }
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a plain http address off loopback is dialled when the deployment says so`() {
    server.`when`(request().withMethod("DELETE")).respond(response().withStatusCode(204))
    val inCluster = Dns { host -> if (host == "sempods.internal") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host) }
    val session = SempodsSession(
      SempodsPodBase.of(name).reachedOverPlaintextAt("http://sempods.internal:${server.port}/api/pod"),
      SempodsRequestAuth.bearer("token-a"),
    )

    sempodsClient { dns(inCluster) }.closing { client ->
      SempodsPod(session, client).contexts().delete("$name/_system/contexts/spaces/default")
    }

    val sent = server.retrieveRecordedRequests(request()).single()
    assertEquals("/api/pod/_system/contexts/spaces/default", sent.path.value)
    assertEquals("sempods.internal:${server.port}", sent.getFirstHeader("Host"))
  }
}
