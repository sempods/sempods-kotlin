package org.sempods.mcp.crypto

import org.sempods.commons.config.Env
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Encryption-at-rest (AES-256-GCM) for the secrets the service must keep **recoverable in
 * plaintext**: the pod OAuth tokens in the vault and the private OAuth signing-key JWK. Hashing is
 * not an option — the service presents the pod tokens at pods' token endpoints and signs with the
 * key — so GCM gives confidentiality plus tamper detection (a flipped DB bit fails the tag instead
 * of yielding garbage that would be sent to a pod).
 *
 * Wire format: `v1:` + base64url(IV[12] ‖ ciphertext+tag). The version prefix versions the
 * **envelope format**. It is **not** a key-id: there is one active [KEY_ENV_VARIABLE], so a key
 * change is a flag-day (existing rows become undecryptable) — graceful key rotation (a
 * `v1:<kid>:…` form) is a later concern (deferred; see docs/mcp/hosted-mcp.md, open
 * questions).
 *
 * Standalone — no application-framework dependency; the JCA is all it needs.
 */
class SecretCipher(keyBytes: ByteArray) {

  init {
    require(keyBytes.size == KEY_LENGTH_BYTES) {
      "MCP secret key must be $KEY_LENGTH_BYTES bytes, got ${keyBytes.size}"
    }
  }

  private val key = SecretKeySpec(keyBytes, "AES")
  private val random = SecureRandom()

  fun encrypt(plaintext: String): String {
    val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(iv + ciphertext)
  }

  /** @throws IllegalArgumentException on unknown format, wrong key, or tampered data. */
  fun decrypt(encrypted: String): String {
    require(encrypted.startsWith(VERSION_PREFIX)) { "unknown secret cipher format" }
    val bytes = Base64.getUrlDecoder().decode(encrypted.removePrefix(VERSION_PREFIX))
    require(bytes.size > IV_LENGTH_BYTES) { "secret ciphertext too short" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      key,
      GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES),
    )
    return try {
      String(cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
      throw IllegalArgumentException("secret decryption failed (wrong key or tampered data)", e)
    }
  }

  /** Encrypt for storage; a null (e.g. an absent refresh token) passes through unchanged. */
  fun encryptMaybe(plaintext: String?): String? = plaintext?.let(::encrypt)

  /** [decrypt] for a nullable column; a null passes through unchanged. */
  fun decryptMaybe(stored: String?): String? = stored?.let(::decrypt)

  companion object {
    private val logger = KotlinLogging.logger {}

    const val KEY_ENV_VARIABLE = "MCP_SECRET_KEY"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION_PREFIX = "v1:"
    private const val KEY_LENGTH_BYTES = 32
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    /**
     * Resolve the key from [KEY_ENV_VARIABLE] (base64, 32 bytes). On a **real** deployment the key
     * is mandatory — a missing key fails startup loudly. "Real" is `isSecure` (an https
     * [SempodsMcpConfig.mcpBaseUrl]) **or** `PRODUCTION=true`, so an instance behind a
     * TLS-terminating proxy (where `mcpBaseUrl` might read http) does not silently fall back to the
     * dev key. On a genuine local / self-host-dev deployment a deterministic dev key is derived so
     * local setups need no configuration; that key must never protect a public instance.
     */
    fun fromEnv(isSecure: Boolean): SecretCipher = SecretCipher(resolveKey(isSecure))

    // TODO: the same defect the pod server's admin fallback had — an environment inferred rather
    //  than a choice made. `!isSecure && !isProduction` still takes the development path for a
    //  deployment served over plain HTTP with `PRODUCTION` unset, which is a first self-host or
    //  anything behind a TLS-terminating proxy, and this key encrypts stored refresh tokens.
    //  `SempodsModule` now takes an explicit `SEMPODS_DEV_ADMIN_FALLBACK`; this wants the same
    //  shape. Not done with it because the admin surface was the published-credential half and
    //  this is not: the key here is derived, not a constant anyone can read off the source.
    private fun resolveKey(isSecure: Boolean): ByteArray {
      val encoded = Env.get(KEY_ENV_VARIABLE)?.takeIf(String::isNotBlank)
      if (encoded != null) {
        val decoded = runCatching { Base64.getDecoder().decode(encoded) }
          .getOrElse { Base64.getUrlDecoder().decode(encoded) }
        require(decoded.size == KEY_LENGTH_BYTES) {
          "$KEY_ENV_VARIABLE must decode to $KEY_LENGTH_BYTES bytes, got ${decoded.size}"
        }
        return decoded
      }
      val isProduction = Env.get("PRODUCTION")?.toBooleanStrictOrNull() == true
      check(!isSecure && !isProduction) {
        "$KEY_ENV_VARIABLE must be set on a public/production deployment (base64, $KEY_LENGTH_BYTES bytes)"
      }
      logger.warn {
        "$KEY_ENV_VARIABLE not set — using the deterministic development key. " +
          "Never run a public instance with this key."
      }
      return MessageDigest.getInstance("SHA-256")
        .digest("sempods-mcp-dev-secret-key".toByteArray(Charsets.UTF_8))
    }
  }
}
