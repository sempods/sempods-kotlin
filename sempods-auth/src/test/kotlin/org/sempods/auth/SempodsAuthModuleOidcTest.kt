package org.sempods.auth

import com.google.inject.Guice
import org.sempods.auth.oidc.OidcRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the module actually binds for a given configuration.
 *
 * The important case is the empty one: a self-hosted identity service without a Google project
 * or an Apple developer account must still boot. Guice would otherwise refuse to inject
 * `Set<OidcProviderClient>` at all, which is why the module declares the set binder even when it
 * contributes nothing to it.
 */
class SempodsAuthModuleOidcTest {

  private val baseConfig = SempodsAuthConfig(
    port = 8091,
    mongoUrl = "mongodb://localhost:27018",
    mongoDbName = "sempods-auth-test",
    idBaseUrl = "https://id.example.invalid",
  )

  private val googleCredentials = SempodsAuthConfig.GoogleCredentials(
    clientId = "test-client-id",
    clientSecret = "test-client-secret",
  )

  private val appleCredentials = SempodsAuthConfig.AppleCredentials(
    teamId = "TEAM123",
    serviceId = "org.sempods.test.service",
    keyId = "KEY123",
    // Never parsed here: AppleOidcClient derives its client secret lazily, on first use.
    privateKeyPem = "-----BEGIN PRIVATE KEY-----\nnot-a-real-key\n-----END PRIVATE KEY-----",
  )

  private fun registryFor(config: SempodsAuthConfig): OidcRegistry =
    Guice.createInjector(SempodsAuthModule(config)).getInstance(OidcRegistry::class.java)

  @Test
  fun `without credentials the service starts with no login providers`() {
    val registry = registryFor(baseConfig)

    assertTrue(registry.providers.isEmpty(), "an unconfigured deployment must still come up")
  }

  @Test
  fun `google credentials register exactly the google provider`() {
    val registry = registryFor(baseConfig.copy(google = googleCredentials))

    assertEquals(setOf("google"), registry.providers.keys)
  }

  @Test
  fun `apple credentials register exactly the apple provider`() {
    val registry = registryFor(baseConfig.copy(apple = appleCredentials))

    assertEquals(setOf("apple"), registry.providers.keys)
  }

  @Test
  fun `both sets of credentials register both providers`() {
    val registry = registryFor(baseConfig.copy(google = googleCredentials, apple = appleCredentials))

    assertEquals(setOf("apple", "google"), registry.providers.keys)
  }
}
