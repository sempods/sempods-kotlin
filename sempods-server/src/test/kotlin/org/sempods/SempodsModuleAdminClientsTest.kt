package org.sempods

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The admin surface is fail-closed by design: with no credentials configured every
 * `_system/admin/…` route answers 503. A development fallback exists so a local run works without
 * hand-picking a secret — these pin that it is *asked for* rather than inferred, and that it can
 * never leak into a deployment.
 */
class SempodsModuleAdminClientsTest {

  @Test
  fun `configured clients are used as they are`() {
    val configured = mapOf("operator" to "sc_operator", "notes-app" to "sc_notes-app")

    assertEquals(
      configured,
      SempodsModule.resolveAdminClients(
        configured = configured,
        fallbackRequested = true,
        isDevelopment = true,
      ),
    )
    assertEquals(
      configured,
      SempodsModule.resolveAdminClients(
        configured = configured,
        fallbackRequested = false,
        isDevelopment = false,
      ),
    )
  }

  @Test
  fun `nothing configured outside development stays fail closed`() {
    val clients = SempodsModule.resolveAdminClients(
      configured = emptyMap(),
      fallbackRequested = false,
      isDevelopment = false,
    )

    assertTrue(clients.isEmpty(), "a production deployment must not receive an implicit credential")
  }

  @Test
  fun `nothing configured in development is fail closed too, unless the fallback is asked for`() {
    // The point of the whole variable: an unset environment is not consent. Before this, a jar
    // built from source and bound publicly accepted the published secret because nobody had set
    // PRODUCTION=true.
    val clients = SempodsModule.resolveAdminClients(
      configured = emptyMap(),
      fallbackRequested = false,
      isDevelopment = true,
    )

    assertTrue(clients.isEmpty(), "a development environment is not by itself a request")
  }

  @Test
  fun `the fallback, asked for in development, seeds the development pair`() {
    val clients = SempodsModule.resolveAdminClients(
      configured = emptyMap(),
      fallbackRequested = true,
      isDevelopment = true,
    )

    assertEquals(
      mapOf(SempodsModule.DEVELOPMENT_ADMIN_CLIENT_ID to SempodsModule.DEVELOPMENT_ADMIN_SECRET),
      clients,
    )
  }

  @Test
  fun `asking for the fallback in production fails the boot`() {
    // Refused rather than ignored: dropping it silently leaves an operator with 503s and a
    // variable that looks set.
    val failure = assertFailsWith<IllegalStateException> {
      SempodsModule.resolveAdminClients(
        configured = emptyMap(),
        fallbackRequested = true,
        isDevelopment = false,
      )
    }

    assertTrue(
      failure.message.orEmpty().contains(SempodsModule.DEV_ADMIN_FALLBACK_ENV_VARIABLE),
      "the message must name the variable to unset: ${failure.message}",
    )
  }

  @Test
  fun `configured pairs win even where the fallback would be refused`() {
    // The check guards the *fallback*, not the boot in general — a production deployment that
    // carries a stray variable and real credentials is still a misconfiguration worth failing on,
    // which is why this asserts the failure rather than the pairs.
    assertFailsWith<IllegalStateException> {
      SempodsModule.resolveAdminClients(
        configured = mapOf("operator" to "sc_operator"),
        fallbackRequested = true,
        isDevelopment = false,
      )
    }
  }

  @Test
  fun `the development secret is the literal a local client is configured with`() {
    // Written out rather than shared: an application that dials this server configures its half
    // independently, so nothing but this assertion stops the two from drifting into a local 401.
    assertEquals("sc_development-admin-secret", SempodsModule.DEVELOPMENT_ADMIN_SECRET)
  }

  @Test
  fun `the fallback variable is spelled the way the env templates spell it`() {
    // Both `local.example.env` files carry this name, and a rename that missed them would show up
    // as a local 503 rather than as anything about a variable.
    assertEquals("SEMPODS_DEV_ADMIN_FALLBACK", SempodsModule.DEV_ADMIN_FALLBACK_ENV_VARIABLE)
  }
}
