package org.sempods.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.SecretCipher
import org.sempods.commons.config.Env
import java.security.MessageDigest
import java.util.Base64

/**
 * Where this server's [SecretCipher] key comes from. It encrypts the PKCE verifier of every
 * sign-in in flight ([PodLoginStateStore]), so every replica of a deployment needs the same one,
 * and changing it fails only the sign-ins in flight — fifteen minutes' worth.
 *
 * | [ENV_VARIABLE] | [DEV_FALLBACK_ENV_VARIABLE] | Boot |
 * |---|---|---|
 * | set | unset | uses the key |
 * | unset | unset | fails: no key |
 * | unset | `true`, in development | uses the development key, with a warning |
 * | either | `true`, with `PRODUCTION=true` | fails: the fallback is development only |
 */
object PodSecretKey {

  private val logger = KotlinLogging.logger {}

  /** Base64 or base64url, [SecretCipher.KEY_BYTES] bytes: `openssl rand -base64 32`. */
  const val ENV_VARIABLE = "SEMPODS_SECRET_KEY"

  /**
   * Arms the development key, which is derived from a seed published with the source. Asked for
   * rather than inferred, as [org.sempods.SempodsModule]'s admin fallback is: a deployment served
   * over plain HTTP, or with `PRODUCTION` unset, is still a deployment.
   */
  const val DEV_FALLBACK_ENV_VARIABLE = "SEMPODS_DEV_SECRET_KEY_FALLBACK"

  fun cipherFromEnv(): SecretCipher = SecretCipher(
    resolveKey(
      configured = Env.get(ENV_VARIABLE),
      fallbackRequested = Env.get(DEV_FALLBACK_ENV_VARIABLE)?.toBooleanStrictOrNull() == true,
      isDevelopment = Env.isDevelopment,
    ),
  )

  internal fun resolveKey(configured: String?, fallbackRequested: Boolean, isDevelopment: Boolean): ByteArray {
    check(!(fallbackRequested && !isDevelopment)) {
      "$DEV_FALLBACK_ENV_VARIABLE arms a key derived from a published seed and must not be set in " +
        "production — configure $ENV_VARIABLE instead"
    }
    if (configured != null) return decode(configured)
    check(fallbackRequested) {
      "$ENV_VARIABLE must be set (base64, ${SecretCipher.KEY_BYTES} bytes: openssl rand -base64 32) — it " +
        "encrypts sign-ins in flight, and replicas must agree on it. Set $DEV_FALLBACK_ENV_VARIABLE=true " +
        "for a local run."
    }
    logger.warn {
      "$DEV_FALLBACK_ENV_VARIABLE is set — sign-ins are encrypted under a key derived from a seed " +
        "published with the source. Development only."
    }
    return MessageDigest.getInstance("SHA-256").digest("sempods-server-dev-secret-key".toByteArray(Charsets.UTF_8))
  }

  private fun decode(encoded: String): ByteArray {
    val decoded = runCatching { Base64.getDecoder().decode(encoded) }
      .getOrElse { Base64.getUrlDecoder().decode(encoded) }
    require(decoded.size == SecretCipher.KEY_BYTES) {
      "$ENV_VARIABLE must decode to ${SecretCipher.KEY_BYTES} bytes, got ${decoded.size}"
    }
    return decoded
  }
}
