package org.sempods.mcp.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.impl.BaseJWSProvider
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Date

/** Test helpers for minting RSA and Ed25519 keys and signing JWTs without any persistence/HTTP. */
object JwtTestSupport {

  fun generateKey(kid: String): RSAKey =
    RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate()

  fun sign(key: RSAKey, claims: JWTClaimsSet): String {
    val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
  }

  /** A JDK Ed25519 key pair and its public JWK. Nimbus's own generator for it needs Tink. */
  class Ed25519Key(val kid: String) {
    val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    // The raw key is the tail of its SubjectPublicKeyInfo (RFC 8410 §4).
    val publicJwk: OctetKeyPair =
      OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(pair.public.encoded.takeLast(32).toByteArray()))
        .keyID(kid).keyUse(KeyUse.SIGNATURE).build()
  }

  fun sign(key: Ed25519Key, claims: JWTClaimsSet): String {
    val header = JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(key.kid).build()
    val signer = object : BaseJWSProvider(setOf(JWSAlgorithm.EdDSA)), JWSSigner {
      override fun sign(header: JWSHeader, signingInput: ByteArray): Base64URL =
        Base64URL.encode(
          Signature.getInstance("Ed25519").run {
            initSign(key.pair.private)
            update(signingInput)
            sign()
          },
        )
    }
    return SignedJWT(header, claims).apply { sign(signer) }.serialize()
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
