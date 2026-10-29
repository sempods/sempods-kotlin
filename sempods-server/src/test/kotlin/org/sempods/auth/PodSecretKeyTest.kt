package org.sempods.auth

import org.sempods.auth.core.SecretCipher
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The development key is asked for, never inferred, and never reaches a deployment. */
class PodSecretKeyTest {

  private val key = ByteArray(SecretCipher.KEY_BYTES) { it.toByte() }

  @Test
  fun `a configured key is used, in base64 or base64url`() {
    for (encoded in listOf(Base64.getEncoder().encodeToString(key), Base64.getUrlEncoder().encodeToString(key))) {
      assertContentEquals(key, PodSecretKey.resolveKey(encoded, fallbackRequested = false, isDevelopment = false))
    }
  }

  @Test
  fun `no key and no fallback fails the boot, on a developer machine too`() {
    for (isDevelopment in listOf(true, false)) {
      val failure = assertFailsWith<IllegalStateException> {
        PodSecretKey.resolveKey(configured = null, fallbackRequested = false, isDevelopment = isDevelopment)
      }
      assertContains(failure.message.orEmpty(), PodSecretKey.ENV_VARIABLE)
    }
  }

  @Test
  fun `the fallback arms the development key in development only`() {
    val developmentKey = PodSecretKey.resolveKey(configured = null, fallbackRequested = true, isDevelopment = true)
    assertEquals(SecretCipher.KEY_BYTES, developmentKey.size)

    for (configured in listOf(null, Base64.getEncoder().encodeToString(key))) {
      val failure = assertFailsWith<IllegalStateException> {
        PodSecretKey.resolveKey(configured, fallbackRequested = true, isDevelopment = false)
      }
      assertContains(failure.message.orEmpty(), PodSecretKey.DEV_FALLBACK_ENV_VARIABLE)
    }
  }

  @Test
  fun `a key of the wrong length is refused`() {
    assertFailsWith<IllegalArgumentException> {
      PodSecretKey.resolveKey(Base64.getEncoder().encodeToString(ByteArray(16)), fallbackRequested = false, isDevelopment = false)
    }
  }

  @Test
  fun `the names are the ones deployments set`() {
    assertEquals("SEMPODS_SECRET_KEY", PodSecretKey.ENV_VARIABLE)
    assertEquals("SEMPODS_DEV_SECRET_KEY_FALLBACK", PodSecretKey.DEV_FALLBACK_ENV_VARIABLE)
  }
}
