package org.sempods.client

import com.nimbusds.oauth2.sdk.pkce.CodeChallenge
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier

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
  val challenge: String get() = CodeChallenge.compute(CodeChallengeMethod.S256, CodeVerifier(verifier)).value

  /** Always `S256`. */
  val method: String get() = CodeChallengeMethod.S256.value

  override fun toString(): String = "SempodsPkce(challenge=$challenge, method=$method)"

  companion object {

    /** A fresh verifier from 32 random bytes, as RFC 7636 §4.1 recommends. */
    @JvmStatic
    fun generate(): SempodsPkce = SempodsPkce(CodeVerifier().value)

    /**
     * A verifier the caller already holds, for example across a restart between the two halves.
     *
     * @throws IllegalArgumentException when [verifier] breaks RFC 7636 §4.1.
     */
    @JvmStatic
    fun of(verifier: String): SempodsPkce = SempodsPkce(CodeVerifier(verifier).value)
  }
}
