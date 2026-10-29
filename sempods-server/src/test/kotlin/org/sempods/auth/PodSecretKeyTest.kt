package org.sempods.auth

import org.sempods.auth.core.SecretCipher
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The development key is asked for, never inferred, and never reaches a deployment. */
class PodSecretKeyTest {

  private val key = SecretCipher(ByteArray(SecretCipher.KEY_BYTES) { it.toByte() })
  private val encoded = Base64.getEncoder().encodeToString(ByteArray(SecretCipher.KEY_BYTES) { it.toByte() })

  @Test
  fun `a configured key is used`() {
    val cipher = PodSecretKey.resolve(encoded, fallbackRequested = false, isDevelopment = false)
    assertEquals("x", cipher.decrypt(key.encrypt("x")))
  }

  @Test
  fun `no key and no fallback fails the boot, on a developer machine too`() {
    for (isDevelopment in listOf(true, false)) {
      val failure = assertFailsWith<IllegalStateException> {
        PodSecretKey.resolve(configured = null, fallbackRequested = false, isDevelopment = isDevelopment)
      }
      assertContains(failure.message.orEmpty(), PodSecretKey.ENV_VARIABLE)
    }
  }

  @Test
  fun `the fallback arms the development key in development only`() {
    PodSecretKey.resolve(configured = null, fallbackRequested = true, isDevelopment = true)

    for (configured in listOf(null, encoded)) {
      val failure = assertFailsWith<IllegalStateException> {
        PodSecretKey.resolve(configured, fallbackRequested = true, isDevelopment = false)
      }
      assertContains(failure.message.orEmpty(), PodSecretKey.DEV_FALLBACK_ENV_VARIABLE)
    }
  }

  @Test
  fun `the names are the ones deployments set`() {
    assertEquals("SEMPODS_SECRET_KEY", PodSecretKey.ENV_VARIABLE)
    assertEquals("SEMPODS_DEV_SECRET_KEY_FALLBACK", PodSecretKey.DEV_FALLBACK_ENV_VARIABLE)
  }
}
