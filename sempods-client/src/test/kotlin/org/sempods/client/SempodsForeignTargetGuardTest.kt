package org.sempods.client

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.sempods.client.net.OutboundRateLimiter
import org.sempods.client.net.SempodsOutboundGuard
import org.sempods.client.net.SempodsRateLimitedException
import org.sempods.client.net.SempodsUrlPolicy
import org.sempods.client.net.SsrfBlockedException

/**
 * The outbound guard, end to end, under a foreign target: what a client installed with one refuses,
 * and that it refuses every hop of a followed redirect.
 *
 * The targets are names, answered by a resolver the test controls, so a name can resolve into a
 * blocked range while the server it would reach is MockServer on loopback.
 */
class SempodsForeignTargetGuardTest : MockPodTest() {

  private val name = "foreign.example.test"

  private val card get() = "http://$name:${server.port}/card"

  private fun guarded(
    hosts: Map<String, String>,
    allowPrivate: Boolean = false,
    trusted: Set<String> = emptySet(),
    rateLimiter: OutboundRateLimiter? = null,
  ): OkHttpClient = sempodsClient(
    guard = SempodsOutboundGuard(
      policy = SempodsUrlPolicy(allowPrivateAddresses = allowPrivate),
      trustedHosts = trusted,
      rateLimiter = rateLimiter,
      resolver = { host -> listOf(InetAddress.getByName(hosts[host] ?: throw UnknownHostException("no test address for '$host'"))) },
    ),
  )

  private fun sent() = server.retrieveRecordedRequests(request()).size

  private fun Throwable.causes() = generateSequence(this) { it.cause }

  @Test
  fun `a name that resolves into a blocked range is refused, and no socket is opened`() {
    server.`when`(request()).respond(response().withStatusCode(200))

    guarded(mapOf(name to "10.0.0.5")).closing { client ->
      val refused = assertThrows<UnknownHostException> { SempodsForeignTarget(client).getText(card, "text/turtle") }
      assertTrue(refused.causes().any { it is SsrfBlockedException }, "$refused")
    }

    assertEquals(0, sent())
  }

  @ParameterizedTest
  @ValueSource(strings = ["http://169.254.169.254/latest/meta-data/", "http://127.0.0.1:{port}/card", "http://[::1]:{port}/card"])
  fun `an address literal is refused before any resolver is asked`(literal: String) {
    server.`when`(request()).respond(response().withStatusCode(200))

    sempodsClient(guard = SempodsOutboundGuard(SempodsUrlPolicy(false), resolver = { fail("nothing is resolved for '$it'") }))
      .closing { client ->
        val refused = assertThrows<SempodsClientException> {
          SempodsForeignTarget(client).getText(literal.replace("{port}", "${server.port}"), "text/turtle")
        }
        assertTrue(refused.causes().any { it is SsrfBlockedException }, "$refused")
      }

    assertEquals(0, sent())
  }

  @Test
  fun `a name the guard vets connects`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))

    guarded(mapOf(name to "127.0.0.1"), allowPrivate = true).closing { client ->
      assertEquals("ok", SempodsForeignTarget(client).getText(card, "text/turtle").body)
    }

    assertEquals(1, sent())
  }

  @Test
  fun `the outbound budget is charged before the request leaves`() {
    server.`when`(request()).respond(response().withStatusCode(200))

    guarded(mapOf(name to "127.0.0.1"), allowPrivate = true, rateLimiter = { false }).closing { client ->
      assertThrows<SempodsRateLimitedException> { SempodsForeignTarget(client).getText(card, "text/turtle") }
    }

    assertEquals(0, sent())
  }

  @ParameterizedTest
  @ValueSource(strings = ["http://169.254.169.254/latest/meta-data/", "http://internal.example.test:{port}/secrets"])
  fun `every hop of a followed redirect is vetted as a call of its own`(inside: String) {
    server.`when`(request().withPath("/card"))
      .respond(response().withStatusCode(302).withHeader("Location", inside.replace("{port}", "${server.port}")))
    server.`when`(request()).respond(response().withStatusCode(200).withBody("inside"))

    // Under a strict policy the first host reaches loopback only as a trusted one; the hop it points to
    // is judged on its own, trusted or not.
    guarded(mapOf(name to "127.0.0.1", "internal.example.test" to "10.0.0.5"), trusted = setOf(name)).closing { client ->
      val refused = assertThrows<IOException> { SempodsForeignTarget(client).followingRedirects(3).getText(card, "text/turtle") }
      assertTrue(refused.causes().any { it is SsrfBlockedException }, "$refused")
    }

    assertEquals(1, sent(), "the redirect was asked for, the place it points to was not")
  }

  @Test
  fun `a client installed without a guard dials whatever it is given`() {
    server.`when`(request()).respond(response().withStatusCode(200).withBody("loopback"))

    sempodsClient().closing { client ->
      assertEquals("loopback", SempodsForeignTarget(client).getText("http://127.0.0.1:${server.port}/card", "text/turtle").body)
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["127.0.0.1", "localhost", "[::1]"])
  fun `a trusted host is exempt from the per-request check, not only from the resolver`(host: String) {
    // The exemption exists for a caller whose every target is deploy-time configuration — an issuer
    // that on a private network answers only at an address the policy otherwise refuses. Wiring it
    // into the resolver alone would leave it useless for exactly those hosts, because an IP literal
    // never reaches a resolver at all. The IPv6 case is the spelling: `URI.getHost()` keeps the
    // brackets and the resolver hook does not, so a set built from either has to match.
    server.`when`(request()).respond(response().withStatusCode(200).withBody("ok"))
    val resolved = host.removeSurrounding("[", "]")

    guarded(mapOf(resolved to resolved), trusted = setOf(resolved)).closing { client ->
      assertEquals("ok", SempodsForeignTarget(client).getText("http://$host:${server.port}/card", "text/turtle").body)
    }

    assertEquals(1, sent())
  }

  @Test
  fun `the exemption covers the trusted host alone`() {
    server.`when`(request()).respond(response().withStatusCode(200))

    guarded(mapOf("127.0.0.1" to "127.0.0.1"), trusted = setOf("127.0.0.1")).closing { client ->
      val refused = assertThrows<SempodsClientException> {
        SempodsForeignTarget(client).getText("http://169.254.169.254/latest/meta-data/", "text/turtle")
      }
      assertTrue(refused.causes().any { it is SsrfBlockedException }, "$refused")
    }

    assertEquals(0, sent())
  }
}
