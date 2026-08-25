package org.sempods.commons.jaxrs.filters

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VaryHeaderUtilTest {

  @Test
  fun `should add value to non-existent header`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns null
    every { responseContext.headers } returns headers

    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")

    assertEquals("Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should append value to existing header`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns "Origin"
    every { responseContext.headers } returns headers

    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")

    assertEquals("Origin, Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should not duplicate existing value`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    headers.putSingle("Vary", "Origin, Accept")

    every { responseContext.getHeaderString("Vary") } returns "Origin, Accept"
    every { responseContext.headers } returns headers

    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")

    assertEquals("Origin, Accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should handle case-insensitive duplicate check`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()
    headers.putSingle("Vary", "Origin, accept")

    every { responseContext.getHeaderString("Vary") } returns "Origin, accept"
    every { responseContext.headers } returns headers

    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")

    assertEquals("Origin, accept", headers.getFirst("Vary"))
  }

  @Test
  fun `should handle multiple existing values`() {
    val responseContext = mockk<ContainerResponseContext>(relaxed = true)
    val headers = MultivaluedHashMap<String, Any>()

    every { responseContext.getHeaderString("Vary") } returns "Origin, User-Agent, Cookie"
    every { responseContext.headers } returns headers

    VaryHeaderUtil.addCommaSeparatedHeaderValue(responseContext, "Vary", "Accept")

    assertEquals("Origin, User-Agent, Cookie, Accept", headers.getFirst("Vary"))
  }
}
