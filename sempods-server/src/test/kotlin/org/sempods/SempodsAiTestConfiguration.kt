package org.sempods

import com.google.inject.spi.Element
import com.google.inject.spi.Elements
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.sempods.commons.config.Env

/**
 * Records production bindings with a test-local provider, independent of the host environment.
 * The scoped mock ends before injector creation starts services or any test sends a request.
 * Callers must not record another configuration concurrently; binding tests use `@Isolated`,
 * and the integration suite records once inside its shared lazy injector.
 */
internal fun sempodsElementsWithAiProvider(provider: String?): List<Element> {
  mockkObject(Env)
  try {
    every { Env.get("AI_PROVIDER") } returns provider
    return Elements.getElements(SempodsModule())
  } finally {
    unmockkObject(Env)
  }
}
