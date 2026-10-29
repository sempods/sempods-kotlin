package org.sempods.auth

import org.sempods.auth.core.SecretCipher
import org.sempods.commons.config.Env

/**
 * Where this server's [SecretCipher] key comes from. It encrypts the PKCE verifier of every
 * sign-in in flight ([PodLoginStateStore]), so every replica of a deployment needs the same one,
 * and changing it fails only the sign-ins in flight — fifteen minutes' worth. Without it the server
 * does not start; a local run takes the one in `local.example.env`.
 */
object PodSecretKey {

  /** Base64 or base64url, [SecretCipher.KEY_BYTES] bytes: `openssl rand -base64 32`. */
  const val ENV_VARIABLE = "SEMPODS_SECRET_KEY"

  fun cipherFromEnv(): SecretCipher = resolve(Env.get(ENV_VARIABLE))

  internal fun resolve(configured: String?): SecretCipher {
    checkNotNull(configured) {
      "$ENV_VARIABLE must be set (base64, ${SecretCipher.KEY_BYTES} bytes: openssl rand -base64 32) — it " +
        "encrypts sign-ins in flight, and replicas must agree on it"
    }
    return SecretCipher.fromBase64(configured)
  }
}
