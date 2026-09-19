package org.sempods.mcp.pods

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.sempods.client.core.SempodsOkHttp
import org.sempods.client.core.net.SempodsOutboundGuard
import org.sempods.client.core.net.OutboundRateLimiter
import org.sempods.client.core.net.SsrfBlockedException
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import java.net.InetAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockserver.integration.ClientAndServer
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards [isRetryablePodFailure] — the one question that decides whether a failure is reported as
 * `pod_error` or as `no_token`/"reconnect this pod".
 *
 * Both refusals are the client core's own, and the classifier finds them on the cause chain: the
 * core wraps a blocked address in its own exception while keeping the cause, and an engine may wrap
 * again. A bare `is` check would miss exactly those and fall through to the misleading reconnect
 * path. The already-wrapped case is covered directly below.
 */
class RetryablePodFailureTest {
  private lateinit var server: ClientAndServer

  @BeforeEach fun setup() {
    server = ClientAndServer.startClientAndServer(0)
    server.`when`(request().withMethod("GET").withPath("/ok"))
      .respond(response().withStatusCode(200).withBody("hi"))
  }
  @AfterEach fun teardown() { server.stop() }

  private fun resolveTo(vararg a: String): (String) -> List<InetAddress> =
    { a.map { InetAddress.getByName(it) } }

  private fun guarded(guard: SempodsOutboundGuard): OkHttpClient =
    SempodsOkHttp.install(OkHttpClient.Builder(), guard, admission = null).build()

  private fun fetch(calls: Call.Factory, url: String) =
    calls.newCall(Request.Builder().url(url).get().build()).execute().close()

  @Test fun `a real SSRF-blocked fetch is classified as transient infra`() {
    val calls = guarded(
      SempodsOutboundGuard(
        policy = PodUrlPolicy(allowLocal = false).rules,
        resolver = resolveTo("10.0.0.1"),
      ),
    )
    val e = assertFailsWith<Exception> { fetch(calls, "http://internal.test:${server.port}/ok") }
    assertTrue(e.isRetryablePodFailure(), "classifier missed the SsrfBlockedException: $e")
  }

  @Test fun `a real rate-limited fetch is classified as transient infra`() {
    val limiter = TokenBucketRateLimiter(1)
    val calls = guarded(
      SempodsOutboundGuard(
        policy = PodUrlPolicy(allowLocal = true).rules,
        rateLimiter = OutboundRateLimiter { limiter.tryAcquire(it.host) },
        resolver = resolveTo("127.0.0.1"),
      ),
    )
    fetch(calls, "http://pod-a.test:${server.port}/ok")
    val e = assertFailsWith<Exception> { fetch(calls, "http://pod-a.test:${server.port}/ok") }
    assertTrue(e.isRetryablePodFailure(), "classifier missed the rate-limit refusal: $e")
  }

  @Test fun `a wrapped transient failure is still classified`() {
    val wrapped = RuntimeException("engine wrapper", SsrfBlockedException("blocked"))
    assertTrue(wrapped.isRetryablePodFailure())
  }

  @Test fun `an unrelated failure is not classified as transient infra`() {
    assertTrue(!RuntimeException("dead token").isRetryablePodFailure())
  }

  @Test fun `a pod refusal is retryable unless it says the grant is finished`() {
    // RFC 6749 §5.2 gives exactly one code that means the grant is gone. Everything else the pod
    // can answer with — and a 5xx that carries no code at all — is worth another attempt.
    assertTrue(PodOAuthException("m", oauthErrorCode = "temporarily_unavailable").isRetryablePodFailure())
    assertTrue(PodOAuthException("m", oauthErrorCode = "server_error").isRetryablePodFailure())
    assertTrue(!PodOAuthException("m", oauthErrorCode = "invalid_grant").isRetryablePodFailure())
    // No code at all says nothing either way, so it does not claim to be retryable.
    assertTrue(!PodOAuthException("m").isRetryablePodFailure())
  }
}
