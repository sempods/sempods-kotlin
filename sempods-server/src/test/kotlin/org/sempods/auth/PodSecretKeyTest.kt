package org.sempods.auth

import org.sempods.auth.core.SecretCipher
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PodSecretKeyTest {

  @Test
  fun `a configured key is used`() {
    val bytes = ByteArray(SecretCipher.KEY_BYTES) { it.toByte() }
    val cipher = PodSecretKey.resolve(Base64.getEncoder().encodeToString(bytes))
    assertEquals("x", cipher.decrypt(SecretCipher(bytes).encrypt("x")))
  }

  @Test
  fun `no key fails the boot`() {
    val failure = assertFailsWith<IllegalStateException> { PodSecretKey.resolve(configured = null) }
    assertContains(failure.message.orEmpty(), PodSecretKey.ENV_VARIABLE)
  }

  @Test
  fun `the name is the one deployments set`() {
    assertEquals("SEMPODS_SECRET_KEY", PodSecretKey.ENV_VARIABLE)
  }
}
