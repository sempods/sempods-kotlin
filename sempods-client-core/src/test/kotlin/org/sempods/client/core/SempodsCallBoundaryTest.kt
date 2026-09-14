package org.sempods.client.core

import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response

/**
 * Every way a session's request can run past the session's interceptors, and what happens then.
 *
 * The policy lives on a client rather than in the session, so a request can meet a client without it,
 * an interceptor that moves it, or a redirect. None of them may send the credential somewhere else,
 * and none of them may turn the request into an anonymous one without saying so.
 */
class SempodsCallBoundaryTest : MockPodTest() {

  private fun alice() = SempodsSession(SempodsPodBase.of("$origin/alice"), SempodsRequestAuth.bearer("token-a"))

  @Test
  fun `a session's request fails closed on a client without the sempods interceptors`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    OkHttpClient().closing { plain ->
      val refused = assertThrows<UnknownHostException> {
        plain.newCall(alice().newRequest("GET", "x").build()).execute().close()
      }
      assertTrue(refused.message!!.contains("sempods-session.invalid"), refused.message)
    }
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `without the placeholder a plain client would send the request anonymously`() {
    // Evidence for the placeholder, not behaviour to keep. A tag alone leaves the pod's URL on the
    // request, and a client without the interceptors sends that without a credential — which the pod
    // answers the way it answers any anonymous caller.
    server.`when`(request()).respond(response().withStatusCode(200))
    OkHttpClient().closing { plain ->
      val tagOnly = alice().newRequest("GET", "x").url("$origin/alice/x").build()
      plain.newCall(tagOnly).execute().use { assertEquals(200, it.code) }
    }
    assertEquals("", server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization"))
  }

  @Test
  fun `an interceptor after the session's that moves the request takes no credential along`() {
    // A consumer's interceptor on the builder runs after the session's: it sees the pod's URL and the
    // credential, and may point the request anywhere. The network interceptor sees where it points.
    val elsewhere = listOf("$origin/bob/stolen", "http://127.0.0.1:${server.port}/alice/x")
    elsewhere.forEach { target ->
      val moving = Interceptor { chain -> chain.proceed(chain.request().newBuilder().url(target).build()) }
      sempodsClient { addInterceptor(moving) }.closing { client ->
        val refused = assertThrows<SempodsClientException>(target) {
          client.newCall(alice().newRequest("GET", "x").build()).execute().close()
        }
        assertTrue(refused.message!!.contains("not under this session's pod"), refused.message)
      }
    }
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `an interceptor ahead of the session's sees the placeholder, and moving it is refused`() {
    // Where OpenTelemetry's call factory puts its own interceptors: ahead of every other one.
    val seen = CopyOnWriteArrayList<String>()
    val moving = Interceptor { chain ->
      seen += chain.request().url.host
      chain.proceed(chain.request().newBuilder().url("$origin/bob/stolen").build())
    }
    val builder = SempodsOkHttp.install(OkHttpClient.Builder())
    builder.interceptors().add(0, moving)

    builder.build().closing { client ->
      val refused = assertThrows<SempodsClientException> {
        client.newCall(alice().newRequest("GET", "x").build()).execute().close()
      }
      assertTrue(refused.message!!.contains("not under this session's pod"), refused.message)
    }
    assertEquals(listOf("sempods-session.invalid"), seen)
    assertEquals(0, server.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a call keeps its session when an interceptor ahead rebuilds the request without its tags`() {
    server.`when`(request()).respond(response().withStatusCode(200))
    val rebuilding = Interceptor { chain ->
      val original = chain.request()
      chain.proceed(
        Request.Builder().url(original.url).headers(original.headers).method(original.method, original.body).build(),
      )
    }
    val builder = SempodsOkHttp.install(OkHttpClient.Builder())
    builder.interceptors().add(0, rebuilding)

    builder.build().closing { client ->
      client.newCall(alice().newRequest("GET", "x").build()).execute().use { assertEquals(200, it.code) }
    }
    assertEquals("Bearer token-a", server.retrieveRecordedRequests(request()).single().getFirstHeader("Authorization"))
  }

  @Test
  fun `a redirect out of the pod is refused although the consumer turned redirects back on`() {
    // OkHttp keeps a same-host redirect's `Authorization`; the pod boundary is a path, not a host.
    server.`when`(request().withPath("/alice/x"))
      .respond(response().withStatusCode(302).withHeader("Location", "$origin/bob/x"))
    server.`when`(request().withPath("/bob/x")).respond(response().withStatusCode(200))

    SempodsOkHttp.install(OkHttpClient.Builder()).followRedirects(true).build().closing { client ->
      val refused = assertThrows<SempodsClientException> {
        client.newCall(alice().newRequest("GET", "x").build()).execute().close()
      }
      assertTrue(refused.message!!.contains("not under this session's pod"), refused.message)
    }
    assertEquals(1, server.retrieveRecordedRequests(request().withPath("/alice/x")).size)
    assertEquals(0, server.retrieveRecordedRequests(request().withPath("/bob/x")).size)
  }

  @Test
  fun `a Host header must name the pod, whoever sets it`() {
    // A server that routes by name follows `Host`, not the address the connection went to.
    val renaming = SempodsRequestAuth { request, _ -> request.header("Host", "bob.example") }
    val byAuthentication = SempodsSession(SempodsPodBase.of("$origin/alice"), renaming)
    server.`when`(request()).respond(response().withStatusCode(200))

    sempodsClient().closing { client ->
      val byCaller = alice().newRequest("GET", "x").header("Host", "bob.example").build()
      listOf(byCaller, byAuthentication.newRequest("GET", "x").build()).forEach { renamed ->
        val refused = assertThrows<SempodsClientException> { client.newCall(renamed).execute().close() }
        assertTrue(refused.message!!.contains("does not name this session's pod"), refused.message)
      }
      assertEquals(0, server.retrieveRecordedRequests(request()).size)

      val own = alice().newRequest("GET", "x").header("Host", "LOCALHOST:${server.port}").build()
      client.newCall(own).execute().use { assertEquals(200, it.code) }
    }
  }

  @Test
  fun `a builder that already carries the interceptors is refused`() {
    // Two sets would nest the retries and take two admission slots per call.
    sempodsClient().closing { once ->
      assertThrows<IllegalStateException> { SempodsOkHttp.install(once.newBuilder()) }
    }
  }
}
