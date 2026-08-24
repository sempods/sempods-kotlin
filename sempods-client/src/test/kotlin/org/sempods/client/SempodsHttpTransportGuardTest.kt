package org.sempods.client

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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
import org.sempods.client.net.OutboundRateLimiter
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsRateLimitedException
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.client.net.SsrfBlockedException
import org.slf4j.event.Level
import java.net.InetAddress
import java.net.URI

/**
 * What the transport refuses to dial, and how the refusal reaches the caller.
 *
 * The two address layers are tested separately on purpose, because each covers what the other
 * cannot: a *name* is only ever caught inside the connection path (where the answer cannot change
 * between check and connect), and an *IP literal* is only ever caught before it, because nothing
 * asks a resolver about a host that is already an address.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SempodsHttpTransportGuardTest {

  private lateinit var mockServer: ClientAndServer
  private lateinit var target: URI

  @BeforeAll
  fun startServer() {
    mockServer = ClientAndServer.startClientAndServer(Configuration.configuration().logLevel(Level.WARN))
    target = URI("http://pod.example.test:${mockServer.port}/alice/thing")
  }

  @AfterAll
  fun stopServer() = mockServer.stop()

  @BeforeEach
  fun resetServer() {
    mockServer.reset()
    mockServer.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))
  }

  private fun transport(
    allowPrivate: Boolean = false,
    resolvesTo: String = "127.0.0.1",
    rateLimiter: OutboundRateLimiter? = null,
    trustedHosts: Set<String> = emptySet(),
  ) = SempodsHttpTransport(
    guard = SempodsOutboundGuard(
      policy = SempodsUrlPolicy(allowPrivateAddresses = allowPrivate),
      trustedHosts = trustedHosts,
      rateLimiter = rateLimiter,
      resolve = { listOf(InetAddress.getByName(resolvesTo)) },
    ),
  )

  private fun get(transport: SempodsHttpTransport, uri: URI = target) =
    transport.send(transport.newRequest(uri).GET().build())

  @Test
  fun `a name resolving into a blocked range is refused inside the connection path`() {
    // The rebinding case: the host is a name, so only the resolver hook can see what it becomes.
    val ex = assertThrows<SempodsClientException> { get(transport(resolvesTo = "10.0.0.5")) }
    assertTrue(ex.message!!.contains("not publicly addressable"), ex.message)
    assertInstanceOf(SsrfBlockedException::class.java, ex.cause, "the cause must stay classifiable")
    assertEquals(0, mockServer.retrieveRecordedRequests(request()).size, "no socket may be opened")
  }

  @Test
  fun `an IP-literal host is refused although no resolver is ever consulted`() {
    // The gap the DNS hook structurally cannot cover: OkHttp connects to a parseable address
    // directly and never calls `Dns`. Without the per-request check this would go through.
    listOf(
      "http://169.254.169.254/latest/meta-data/",
      "http://127.0.0.1:${mockServer.port}/x",
      "http://[::1]/x",
      "http://2130706433/x",
    ).forEach { raw ->
      val ex = assertThrows<SempodsClientException>("must refuse $raw") {
        // A resolver that would happily return a public address: only the literal check can refuse.
        get(transport(resolvesTo = "93.184.216.34"), URI(raw))
      }
      assertTrue(ex.message!!.contains("not publicly addressable"), "for $raw: ${ex.message}")
      assertInstanceOf(SsrfBlockedException::class.java, ex.cause, "for $raw")
    }
    assertEquals(0, mockServer.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `mixed records are refused rather than raced`() {
    // Filtering to the public subset would let an attacker's DNS play failover roulette.
    val transport = SempodsHttpTransport(
      guard = SempodsOutboundGuard(
        policy = SempodsUrlPolicy(allowPrivateAddresses = false),
        resolve = { listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")) },
      ),
    )
    assertThrows<SempodsClientException> { get(transport) }
    assertEquals(0, mockServer.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `a non-HTTP scheme is refused before anything is dialled`() {
    val ex = assertThrows<SempodsClientException> {
      get(transport(), URI("file:///etc/passwd"))
    }
    assertTrue(ex.message!!.contains("HTTP(S)"), ex.message)
  }

  @Test
  fun `a vetted host connects`() {
    val transport = transport(resolvesTo = "127.0.0.1", allowPrivate = true)
    assertEquals(200, get(transport).statusCode)
    assertEquals(1, mockServer.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `the rate limiter refuses before the request leaves`() {
    val transport = transport(allowPrivate = true, rateLimiter = { false })
    assertThrows<SempodsRateLimitedException> { get(transport) }
    assertEquals(0, mockServer.retrieveRecordedRequests(request()).size)
  }

  @Test
  fun `an unguarded transport keeps dialling whatever it is given`() {
    // Opt-in: consumers that only reach pods they configured themselves lost nothing.
    val plain = SempodsHttpTransport()
    val uri = URI("http://127.0.0.1:${mockServer.port}/x")
    assertEquals(200, plain.send(plain.newRequest(uri).GET().build()).statusCode)
  }

  @Test
  fun `a trusted host is exempt from the per-request check, not only from the resolver`() {
    // The exemption exists for one caller: a transport whose every request targets a deploy-time
    // configured issuer, which on a private network is reachable only at an address the policy
    // otherwise refuses. Wiring it into the resolver alone leaves it useless for exactly the hosts
    // that need it — an IP literal or a loopback name never reaches a resolver at all.
    listOf("127.0.0.1", "localhost").forEach { host ->
      val trusting = transport(trustedHosts = setOf(host))
      val uri = URI("http://$host:${mockServer.port}/x")
      assertEquals(200, trusting.send(trusting.newRequest(uri).GET().build()).statusCode, "for $host")
    }
  }

  @Test
  fun `an IPv6-literal trusted host matches whether or not the brackets are kept`() {
    // `URI.getHost()` keeps the brackets, the engine's host does not. A set built from either
    // spelling has to match, or the exemption silently never fires for an IPv6 issuer.
    val guard = SempodsOutboundGuard(
      policy = SempodsUrlPolicy(allowPrivateAddresses = false),
      trustedHosts = setOf("::1"),
      resolve = { listOf(InetAddress.getByName("::1")) },
    )
    val trusting = SempodsHttpTransport(guard = guard)
    val uri = URI("http://[::1]:${mockServer.port}/x")
    assertEquals(200, trusting.send(trusting.newRequest(uri).GET().build()).statusCode)
  }

  @Test
  fun `the exemption covers the host only — a untrusted host and a bad scheme are still refused`() {
    val trusting = transport(trustedHosts = setOf("127.0.0.1"))
    assertThrows<SempodsClientException> {
      trusting.send(trusting.newRequest(URI("http://169.254.169.254/meta")).GET().build())
    }
    val ex = assertThrows<SempodsClientException> {
      trusting.send(trusting.newRequest(URI("file:///etc/passwd")).GET().build())
    }
    assertTrue(ex.message!!.contains("HTTP(S)"), ex.message)
  }
}
