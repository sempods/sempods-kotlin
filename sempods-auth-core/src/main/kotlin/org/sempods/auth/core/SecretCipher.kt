package org.sempods.auth.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption at rest (AES-256-GCM) for a secret a service must keep **recoverable in plaintext**:
 * a token it presents somewhere later, a private key it signs with, a PKCE verifier it sends to a
 * token endpoint. Where the value is only ever compared, its SHA-256 is the better answer and this
 * class is not needed. GCM gives confidentiality and tamper detection: a flipped bit in the
 * database fails the tag instead of yielding garbage that would be sent on.
 *
 * Wire format: `v1:` + base64url(IV[12] ‖ ciphertext+tag). The prefix versions the **envelope**,
 * not the key: there is one key per instance, so changing it makes every existing row
 * undecryptable. Graceful key rotation is proposed in
 * https://github.com/sempods/sempods-kotlin/issues/141.
 *
 * Where the key comes from is the calling service's decision — an environment variable, a secret
 * store, a development fallback — and so is what to do about a row it cannot decrypt.
 *
 * @param keyBytes exactly [KEY_BYTES] bytes.
 */
class SecretCipher(keyBytes: ByteArray) {

  init {
    require(keyBytes.size == KEY_BYTES) { "secret key must be $KEY_BYTES bytes, got ${keyBytes.size}" }
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

  /**
   * Throws, because callers disagree about what a failure means: a token row that will not
   * decrypt is one to skip and reconnect, a signing key that will not decrypt is a
   * misconfiguration to stop on. A `null` would also read like the absent value [decryptMaybe]
   * passes through.
   *
   * @throws IllegalArgumentException on unknown format, wrong key, or tampered data.
   */
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
    /** The key length AES-256 takes. */
    const val KEY_BYTES = 32

    /**
     * A cipher under [encoded], base64 or base64url — what `openssl rand -base64 32` prints.
     *
     * @throws IllegalArgumentException when it does not decode to [KEY_BYTES] bytes.
     */
    @JvmStatic
    fun fromBase64(encoded: String): SecretCipher =
      SecretCipher(runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { Base64.getUrlDecoder().decode(encoded) })

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VERSION_PREFIX = "v1:"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128
  }
}
