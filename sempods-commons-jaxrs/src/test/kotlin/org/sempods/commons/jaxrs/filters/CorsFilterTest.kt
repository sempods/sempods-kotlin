package org.sempods.commons.jaxrs.filters

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The allowlist as a constructor parameter, rather than derived from a registry of applications.
 * What is pinned here is that an explicit origin set behaves the way a derived one did, and that it
 * is normalised on the way in.
 */
class CorsFilterTest {

  @Test
  fun `should allow a configured origin with credentials`() {
    val headers = corsHeaders(
      filter = CorsFilter(setOf("https://app.example.org")),
      origin = "https://app.example.org",
    )

    assertEquals("https://app.example.org", headers.getFirst("Access-Control-Allow-Origin"))
    assertEquals("true", headers.getFirst("Access-Control-Allow-Credentials"))
    assertEquals("Origin", headers.getFirst("Vary"))
  }

  @Test
  fun `should normalise the configured origin`() {
    // A trailing slash and a path are the two ways the same origin gets written down; the
    // browser sends neither, so both have to reduce to `scheme://authority` before comparison.
    val headers = corsHeaders(
      filter = CorsFilter(setOf("https://app.example.org/")),
      origin = "https://app.example.org",
    )

    assertEquals("https://app.example.org", headers.getFirst("Access-Control-Allow-Origin"))
    assertEquals("true", headers.getFirst("Access-Control-Allow-Credentials"))
  }

  @Test
  fun `should fall back to wildcard without credentials for an unlisted origin`() {
    val headers = corsHeaders(
      filter = CorsFilter(setOf("https://app.example.org")),
      origin = "https://evil.example.org",
    )

    assertEquals("*", headers.getFirst("Access-Control-Allow-Origin"))
    assertNull(headers.getFirst("Access-Control-Allow-Credentials"))
  }

  @Test
  fun `should stay out of the way when there is no Origin header`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    val requestContext = mockk<ContainerRequestContext>()
    every { requestContext.getHeaderString("Origin") } returns null
    every { responseContext.headers } returns headers

    CorsFilter(setOf("https://app.example.org")).filter(requestContext, responseContext)

    assertEquals(0, headers.size)
  }

  private fun corsHeaders(
    filter: CorsFilter,
    origin: String,
  ): MultivaluedHashMap<String, Any> {
    val headers = MultivaluedHashMap<String, Any>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    every { responseContext.headers } returns headers
    every { responseContext.getHeaderString("Vary") } returns null

    val requestContext = mockk<ContainerRequestContext>()
    every { requestContext.getHeaderString("Origin") } returns origin
    every { requestContext.getHeaderString("Sec-Fetch-Mode") } returns "cors"
    every { requestContext.getHeaderString("Sec-Fetch-Site") } returns "cross-site"
    every { requestContext.getHeaderString("Authorization") } returns null
    every { requestContext.getHeaderString("Cookie") } returns null
    every { requestContext.getHeaderString("Access-Control-Request-Headers") } returns null
    every { requestContext.method } returns "GET"

    filter.filter(requestContext, responseContext)
    return headers
  }
}
