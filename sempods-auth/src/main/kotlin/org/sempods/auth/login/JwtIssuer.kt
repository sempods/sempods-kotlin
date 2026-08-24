package org.sempods.auth.login

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.auth.core.SigningKeys
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Issues the identity tokens this service is here to produce, and publishes the keys that verify
 * them.
 *
 * The key comes from [SigningKeys] and therefore survives a restart. It used to be generated per
 * process, which meant every deploy invalidated every token this service had ever signed and
 * logged everyone out — the pod server solved that long ago and this side never did.
 *
 * [issueIdToken] is an OIDC `id_token`: audienced to the client that asked for it, carrying the
 * `nonce` from the request, so a copy is worth nothing anywhere but at its intended recipient.
 * There was a second shape until `/login` was removed — no `aud`, therefore valid at every pod that
 * trusts this issuer. That was the feature (one login, many pods) and it was also what made a
 * leaked one worth stealing.
 */
class JwtIssuer(
  private val issuer: String,
  private val signingKeys: SigningKeys,
) {

  /** JWKS JSON containing the public half of every known key — served at `/.well-known/jwks.json`. */
  val jwksJson: String get() = signingKeys.jwksJson

  /**
   * An OIDC `id_token` (OIDC Core §2).
   *
   * @param audience the `client_id` that requested it. This is the claim that turns a universal
   *   bearer credential into one useful in a single place, and it is why a token delivered to the
   *   wrong recipient stops being an account takeover.
   * @param nonce echoed from the authorization request so the client can tie this response to it
   *   and reject a replayed one. Absent only when the client did not send one.
   * @param alsoKnownAs every equivalent identity URI known for this person. Sempods-specific and
   *   safely ignored by a standard consumer; a pod matches grants against it.
   */
  fun issueIdToken(
    webIdUri: String,
    audience: String,
    alsoKnownAs: List<String>,
    nonce: String? = null,
    issuedAt: Instant = Instant.now(),
  ): String {
    val claims = JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(webIdUri)
      .audience(audience)
      .claim("webid", webIdUri)
      .claim("also_known_as", alsoKnownAs)
      .jwtID(UUID.randomUUID().toString())
      .issueTime(Date.from(issuedAt))
      .expirationTime(Date.from(issuedAt.plusSeconds(ID_TOKEN_TTL_SECONDS)))
      .apply { nonce?.let { claim("nonce", it) } }
      .build()
    return sign(claims)
  }

  /**
   * An OAuth access token for this issuer's own surface (RFC 9068).
   *
   * A token response must carry one — RFC 6749 §5.1 makes `access_token` required, and OIDC Core
   * §3.1.3.3 adds the `id_token` to that rather than replacing it. A response with `token_type`
   * and no token is also self-contradictory, and libraries that validate the shape (`oauth4webapi`,
   * which this project's own browser SDK uses) reject it outright.
   *
   * **It authorizes nothing today.** This service has no protected resource: a WebID document at
   * `/e/<hash>` is public Linked Data by design, which is what makes it dereferenceable at all.
   * The token exists so that the response conforms and so that a relying party's library accepts
   * it — a `/userinfo` endpoint, or the profile management sketched in `identity-service.md`
   * §"Open Questions", would be its first real consumer.
   *
   * Two things keep it from being mistaken for the identity token it travels beside, because a pod
   * verifying an identity JWT checks the issuer, the expiry and the signature — all three of which
   * this token also satisfies:
   *
   * - `typ: at+jwt` in the JOSE header, the RFC 9068 marker for a JWT access token. The identity
   *   token carries plain `JWT`, so the two are distinguishable before a single claim is read.
   * - `aud` names **this service**, the resource it is for — not the client, which is what the
   *   `id_token` names. A verifier that checks either one cannot confuse them.
   *
   * `also_known_as` is deliberately absent: it is what a pod resolves grants against, and this
   * token is not an identity assertion.
   */
  fun issueAccessToken(
    webIdUri: String,
    scopes: Collection<String>,
    issuedAt: Instant = Instant.now(),
  ): String {
    val claims = JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(webIdUri)
      .audience(issuer)
      .claim("scope", scopes.joinToString(" "))
      .jwtID(UUID.randomUUID().toString())
      .issueTime(Date.from(issuedAt))
      .expirationTime(Date.from(issuedAt.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)))
      .build()
    return sign(claims, JOSEObjectType("at+jwt"))
  }


  private fun sign(claims: JWTClaimsSet, type: JOSEObjectType = JOSEObjectType.JWT): String {
    val header = JWSHeader.Builder(signingKeys.algorithm)
      .keyID(signingKeys.keyId)
      // `typ` is what lets a verifier reject a token of the wrong kind outright rather than
      // discovering the mismatch claim by claim.
      .type(type)
      .build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(signingKeys.signingKey())) }.serialize()
  }

  private companion object {
    /** Short on purpose: this token is exchanged for a pod-scoped one, never used for data access. */
    private const val ID_TOKEN_TTL_SECONDS = 15L * 60

    /** Matches the id_token it travels with; nothing accepts it, so a longer life would buy nothing. */
    private const val ACCESS_TOKEN_TTL_SECONDS = 15L * 60
  }
}
