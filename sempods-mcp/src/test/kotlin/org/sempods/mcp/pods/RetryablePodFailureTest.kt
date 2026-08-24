package org.sempods.mcp.pods

import org.sempods.client.SempodsHttpTransport
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.OutboundRateLimiter
import org.sempods.client.net.SsrfBlockedException
import org.sempods.commons.ratelimit.TokenBucketRateLimiter
import io.ktor.client.request.get
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
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
 * Empirically the hardened client surfaces both the SSRF-block and the rate-limit throwable at the
 * top level (not wrapped) on today's fetch path, so the classifier would work with a bare `is`
 * check — but it walks the cause chain defensively so a future Ktor/OkHttp upgrade (or an added
 * async hop) that starts wrapping cannot silently demote one to the misleading reconnect path. The
 * wrapped case is covered directly below.
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

  private fun fetch(transport: SempodsHttpTransport, url: String) =
    transport.send(transport.newRequest(URI(url)).GET().build())

  @Test fun `a real SSRF-blocked fetch is classified as transient infra`() {
    val transport = SempodsHttpTransport(
      guard = SempodsOutboundGuard(
        policy = PodUrlPolicy(allowLocal = false).rules,
        resolve = resolveTo("10.0.0.1"),
      ),
    )
    val e = assertFailsWith<Exception> { fetch(transport, "http://internal.test:${server.port}/ok") }
    assertTrue(e.isRetryablePodFailure(), "classifier missed the SsrfBlockedException: $e")
  }

  @Test fun `a real rate-limited fetch is classified as transient infra`() {
    val limiter = TokenBucketRateLimiter(1)
    val transport = SempodsHttpTransport(
      guard = SempodsOutboundGuard(
        policy = PodUrlPolicy(allowLocal = true).rules,
        rateLimiter = OutboundRateLimiter { limiter.tryAcquire(it.host) },
        resolve = resolveTo("127.0.0.1"),
      ),
    )
    fetch(transport, "http://pod-a.test:${server.port}/ok")
    val e = assertFailsWith<Exception> { fetch(transport, "http://pod-a.test:${server.port}/ok") }
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
