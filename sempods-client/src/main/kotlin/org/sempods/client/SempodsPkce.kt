package org.sempods.client

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One Proof Key for Code Exchange (RFC 7636) with the `S256` method, the only one a pod accepts.
 *
 * ```java
 * SempodsPkce pkce = SempodsPkce.generate();
 * HttpUrl consent = authorization.authorizationUrl(clientId, redirectUri, scope, state, pkce);
 * // ... the browser comes back with a code ...
 * tokens.authorizationCode(clientId, code, redirectUri, pkce.getVerifier());
 * ```
 *
 * Use one per authorization. [toString] leaves out [verifier], which redeems the code.
 */
class SempodsPkce private constructor(
  /** The secret the token request sends: 43 to 128 characters of `[A-Za-z0-9-._~]` (RFC 7636 §4.1). */
  val verifier: String,
) {

  /** `BASE64URL(SHA-256(verifier))` without padding (RFC 7636 §4.2), for the authorization request. */
  val challenge: String = BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

  /** Always `S256`. */
  val method: String get() = METHOD

  override fun toString(): String = "SempodsPkce(challenge=$challenge, method=$METHOD)"

  companion object {

    private const val METHOD = "S256"

    private val BASE64URL = Base64.getUrlEncoder().withoutPadding()

    private val RANDOM = SecureRandom()

    private val VERIFIER = Regex("[A-Za-z0-9._~-]{43,128}")

    /** A fresh verifier from 32 random bytes, as RFC 7636 §4.1 recommends. */
    @JvmStatic
    fun generate(): SempodsPkce = SempodsPkce(BASE64URL.encodeToString(ByteArray(32).also(RANDOM::nextBytes)))

    /**
     * A verifier the caller already holds, for example across a restart between the two halves.
     *
     * @throws IllegalArgumentException when [verifier] breaks RFC 7636 §4.1.
     */
    @JvmStatic
    fun of(verifier: String): SempodsPkce {
      require(VERIFIER.matches(verifier)) { "A PKCE verifier is 43 to 128 characters of [A-Za-z0-9-._~] (RFC 7636 §4.1)." }
      return SempodsPkce(verifier)
    }
  }
}
