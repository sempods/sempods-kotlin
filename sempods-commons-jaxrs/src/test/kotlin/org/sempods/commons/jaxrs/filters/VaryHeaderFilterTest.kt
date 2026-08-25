package org.sempods.commons.jaxrs.filters

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaryHeaderFilterTest {

  private val filter = VaryHeaderFilter()

  @Test
  fun `should add Accept to Vary header when no existing Vary header`() {
    val requestContext = mockk<ContainerRequestContext>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns null
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    assertEquals("Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should append Accept to existing Vary header`() {
    val requestContext = mockk<ContainerRequestContext>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns "Origin"
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    assertEquals("Origin, Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should not duplicate Accept in Vary header`() {
    val requestContext = mockk<ContainerRequestContext>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    // Pre-populate the header to simulate it already being set
    headers.putSingle("Vary", "Origin, Accept")

    every { responseContext.getHeaderString("Vary") } returns "Origin, Accept"
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    // Should not modify the header since Accept is already present
    // The header should still be "Origin, Accept" unchanged
    assertEquals("Origin, Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should handle case-insensitive Accept check`() {
    val requestContext = mockk<ContainerRequestContext>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    // Pre-populate the header to simulate it already being set
    headers.putSingle("Vary", "Origin, accept")

    every { responseContext.getHeaderString("Vary") } returns "Origin, accept"
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    // Should not modify the header since "accept" (lowercase) is already present
    assertEquals("Origin, accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should handle multiple existing Vary values`() {
    val requestContext = mockk<ContainerRequestContext>()
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns "Origin, User-Agent, Cookie"
    every { responseContext.headers } returns headers

    filter.filter(requestContext, responseContext)

    assertEquals("Origin, User-Agent, Cookie, Accept", headers.getFirst("Vary"))
  }
}
