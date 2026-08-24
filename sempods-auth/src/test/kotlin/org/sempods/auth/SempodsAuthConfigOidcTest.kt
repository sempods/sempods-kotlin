package org.sempods.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Login providers are configuration, not code. These pin the two rules that matter: nothing
 * configured is a valid deployment, and something *half* configured is not silently the same as
 * nothing.
 *
 * Each test states the entire environment it means. Reading the real one would make the outcome
 * depend on what the developer or the CI runner happens to export — and a machine set up to run
 * sempods-auth locally exports exactly these names.
 */
class SempodsAuthConfigOidcTest {

  private val googleEnv = mapOf(
    "GOOGLE_OIDC_CLIENT_ID" to "test-client-id",
    "GOOGLE_OIDC_CLIENT_SECRET" to "test-client-secret",
  )

  private val appleEnv = mapOf(
    "APPLE_OIDC_TEAM_ID" to "TEAM123",
    "APPLE_OIDC_SERVICE_ID" to "org.sempods.test.service",
    "APPLE_OIDC_KEY_ID" to "KEY123",
    "APPLE_PRIVATE_KEY_PEM" to "-----BEGIN PRIVATE KEY-----\nnot-a-real-key\n-----END PRIVATE KEY-----",
  )

  private fun configOf(vararg env: Map<String, String>): SempodsAuthConfig {
    val merged = env.fold(emptyMap<String, String>()) { acc, next -> acc + next }
    return SempodsAuthConfig.fromEnv(lookup = merged::get)
  }

  @Test
  fun `no credentials configured is a valid deployment`() {
    val config = configOf()

    assertNull(config.google)
    assertNull(config.apple)
  }

  @Test
  fun `google alone is configured on its own`() {
    val config = configOf(googleEnv)

    assertEquals("test-client-id", assertNotNull(config.google).clientId)
    assertEquals("test-client-secret", config.google!!.clientSecret)
    assertNull(config.apple, "apple must not appear just because google did")
  }

  @Test
  fun `apple alone is configured on its own`() {
    val config = configOf(appleEnv)

    assertEquals("TEAM123", assertNotNull(config.apple).teamId)
    assertEquals("org.sempods.test.service", config.apple!!.serviceId)
    assertNull(config.google)
  }

  @Test
  fun `both can be configured together`() {
    val config = configOf(googleEnv, appleEnv)

    assertNotNull(config.google)
    assertNotNull(config.apple)
  }

  @Test
  fun `a half-configured provider stays disabled rather than half-alive`() {
    // The likeliest deployment mistake: one of the pair pasted, the other forgotten. Treating it
    // as configured would fail at the first login attempt instead of being reported at boot.
    val config = configOf(googleEnv - "GOOGLE_OIDC_CLIENT_SECRET")

    assertNull(config.google)
  }

  @Test
  fun `apple needs all four of its values`() {
    val config = configOf(appleEnv - "APPLE_PRIVATE_KEY_PEM")

    assertNull(config.apple)
  }

  @Test
  fun `the domain association stands apart from the apple credentials`() {
    // The credentials carry Apple login on their own: Apple's portal no longer asks for the
    // domain check, so the normal deployment sets the four values and no verification token at
    // all. Grouping the two would make that normal case impossible to express.
    val onlyAssociation = configOf(mapOf("APPLE_DOMAIN_ASSOCIATION" to "verification-token"))

    assertEquals("verification-token", onlyAssociation.appleDomainAssociation)
    assertNull(onlyAssociation.apple, "a verification token alone is not a login")

    val credentialsOnly = configOf(appleEnv)

    assertNotNull(credentialsOnly.apple, "apple login must not wait on the verification token")
    assertNull(credentialsOnly.appleDomainAssociation)
  }

  @Test
  fun `the rest of the configuration keeps its defaults`() {
    val config = configOf()

    assertEquals(8091, config.port)
    assertEquals("sempods-auth", config.mongoDbName)
    assertEquals("https://id.sempods.org", config.idBaseUrl)
  }

  @Test
  fun `configured values win over the defaults`() {
    val config = configOf(mapOf("PORT" to "9999", "ID_BASE_URL" to "https://id.example.invalid"))

    assertEquals(9999, config.port)
    assertEquals("https://id.example.invalid", config.idBaseUrl)
  }

  @Test
  fun `the id base URL carries no trailing slash, whatever the environment says`() {
    // Every use appends a path with its own leading slash, so a trailing one yields `//` in a
    // WebID URI and in the redirect address registered with Apple. It is also the `issuer` an
    // OIDC client compares byte for byte between the discovery document and the token — and the
    // two are built in different places, so normalising once is what keeps them agreeing.
    val trailing = configOf(mapOf("ID_BASE_URL" to "https://id.example.invalid/"))

    assertEquals("https://id.example.invalid", trailing.idBaseUrl)
    assertEquals("https://id.example.invalid", configOf(mapOf("ID_BASE_URL" to "https://id.example.invalid")).idBaseUrl)
  }

  @Test
  fun `a hand-built config with a trailing slash fails loudly`() {
    // The local main and the tests bypass `fromEnv`. Without this the mistake would surface as a
    // WebID with a doubled separator rather than as an error.
    assertFailsWith<IllegalArgumentException> {
      SempodsAuthConfig(
        port = 8091,
        mongoUrl = "mongodb://localhost:27018",
        mongoDbName = "x",
        idBaseUrl = "https://id.example.invalid/",
      )
    }
  }
}
