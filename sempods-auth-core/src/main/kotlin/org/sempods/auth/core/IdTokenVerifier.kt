package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

/**
 * Verifies a provider's `id_token`: signature against the provider's JWKS, then `aud`, `exp` and
 * `iss`.
 *
 * Shared by every [OidcProviderClient] because the checks are the same and none of them is visible
 * at the call site when it is missing. `iss` in particular is load-bearing rather than decorative:
 * for a login without an email address the WebID is `sha256("$iss:$sub")`
 * (`LoginService` in the identity service), so an unchecked issuer would let a token minted
 * elsewhere claim another person's identity URI. Returning a hardcoded constant — what both
 * clients did before — looks equivalent to checking one and is not.
 *
 * [jwksUrl] is fetched lazily by [JWKSourceBuilder] and cached with its own refresh policy, so one
 * instance per provider is intended.
 *
 * @param canonicalIssuer the spelling written into [OidcClaims.iss], and always accepted.
 * @param alsoAccepted further spellings the provider is known to mint. Google issues both
 *   `https://accounts.google.com` and the bare `accounts.google.com`; since the value feeds a hash
 *   that becomes an identity, the same person must not get two WebIDs depending on which one
 *   arrived — accepted here, normalised to [canonicalIssuer] on the way out.
 */
class IdTokenVerifier(
  jwksUrl: String,
  private val canonicalIssuer: String,
  expectedAudience: String,
  alsoAccepted: Set<String> = emptySet(),
) {

  private val acceptedIssuers: Set<String> = alsoAccepted + canonicalIssuer

  private val processor = DefaultJWTProcessor<SecurityContext>().apply {
    val jwkSource: JWKSource<SecurityContext> = JWKSourceBuilder
      .create<SecurityContext>(URI.create(jwksUrl).toURL())
      .build()
    jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
    // `aud`, and `exp`/`nbf` where present. `iss` is checked below instead, where more than one
    // spelling can be accepted, so the exact-match set is `null`.
    //
    // `exp` is named as required rather than left to the presence check: Nimbus validates an
    // expiry it finds but does not insist there is one, so a token minted without it would verify
    // forever. `iss` and `sub` are required because both feed the WebID — an absent one would
    // otherwise hash as "null".
    jwtClaimsSetVerifier =
      DefaultJWTClaimsVerifier(expectedAudience, null, setOf("iss", "sub", "exp"))
  }

  /**
   * @return the verified claims, with `iss` replaced by [canonicalIssuer].
   * @throws Exception when the signature, `aud`, `exp`, `iss` or a required claim does not hold.
   */
  fun verify(idToken: String): JWTClaimsSet {
    val claims = processor.process(SignedJWT.parse(idToken), null)
    check(claims.issuer in acceptedIssuers) {
      // The rejected value is the provider's, not the user's, so naming it is safe and is the
      // only thing that makes this diagnosable.
      "id_token issuer '${claims.issuer}' is not one of $acceptedIssuers"
    }
    return JWTClaimsSet.Builder(claims).issuer(canonicalIssuer).build()
  }

  companion object {

    /**
     * A claim that means "yes" as either a JSON boolean or the string `"true"`.
     *
     * Apple sends `email_verified` and `is_private_email` in both spellings depending on the
     * response, and Nimbus' `getBooleanClaim` throws on the string form — so the shape of one
     * field would decide whether a login lands in the email namespace or the anonymous OIDC one.
     * Anything else, absent included, counts as "no".
     */
    fun isTrue(raw: Any?): Boolean = when (raw) {
      is Boolean -> raw
      is String -> raw.equals("true", ignoreCase = true)
      else -> false
    }

    fun isEmailVerified(claims: JWTClaimsSet): Boolean = isTrue(claims.getClaim("email_verified"))

    /** The verified email address, or `null` when absent, blank or unverified. */
    fun verifiedEmail(claims: JWTClaimsSet): String? =
      (claims.getClaim("email") as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && isEmailVerified(claims) }
  }
}
