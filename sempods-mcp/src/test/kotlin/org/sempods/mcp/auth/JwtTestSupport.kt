package org.sempods.mcp.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

/** Test helpers for minting RSA keys and signing JWTs without any persistence/HTTP. */
object JwtTestSupport {

  fun generateKey(kid: String): RSAKey =
    RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate()

  fun sign(key: RSAKey, claims: JWTClaimsSet): String {
    val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
  }

  fun webIdClaims(issuer: String, webId: String, expiresInSeconds: Long = 300): JWTClaimsSet {
    val now = Instant.now()
    return JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(webId)
      .claim("webid", webId)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(expiresInSeconds)))
      .build()
  }
}
