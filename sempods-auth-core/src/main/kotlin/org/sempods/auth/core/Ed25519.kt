package org.sempods.auth.core

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.crypto.impl.BaseJWSProvider
import com.nimbusds.jose.crypto.impl.CriticalHeaderParamsDeferral
import com.nimbusds.jose.jca.JCAContext
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.JWSVerifierFactory
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Base64URL
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 signatures for [JwtVerifier], checked by the JDK.
 *
 * Nimbus has an `Ed25519Verifier`, but its JWT processor never reaches it: the key selector turns
 * every JWK into a `java.security.Key`, which an `OctetKeyPair` refuses to become, and the default
 * verifier factory knows RSA, EC and HMAC only. So an Ed25519 key drops out before verification,
 * with or without Tink. The two classes below close that gap — the selector hands the processor a
 * JDK `EdECPublicKey`, the factory answers it with a verifier over `Signature("Ed25519")`.
 *
 * Ed25519 only. `EdDSA` with an Ed448 key matches nothing here and stays inconclusive.
 */
internal object Ed25519 {

  /** `EdDSA` (RFC 8037) and its fully-specified successor `Ed25519` (RFC 9864). */
  val ALGORITHMS: Set<JWSAlgorithm> = setOf(JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519)

  /** DER prefix of an Ed25519 SubjectPublicKeyInfo (RFC 8410 §4); the raw 32-byte key follows it. */
  private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

  /** The JDK key for an Ed25519 JWK, or `null` for any other curve or a malformed `x`. */
  fun publicKey(jwk: OctetKeyPair): PublicKey? {
    if (jwk.curve != Curve.Ed25519) return null
    val x = jwk.decodedX.takeIf { it.size == 32 } ?: return null
    return try {
      KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(SPKI_PREFIX + x))
    } catch (_: GeneralSecurityException) {
      null
    }
  }
}

/**
 * Nimbus's selector, plus Ed25519 keys as JDK keys.
 *
 * Matching stays nimbus's: `createJWKMatcher` checks the algorithm is allowed and builds the
 * `kid`/key-type/use matcher for the ED family. Only the conversion it cannot do is done here.
 */
internal class Ed25519AwareKeySelector(
  algorithms: Set<JWSAlgorithm>,
  private val source: JWKSource<SecurityContext>,
) : JWSVerificationKeySelector<SecurityContext>(algorithms, source) {

  override fun selectJWSKeys(header: JWSHeader, context: SecurityContext?): List<Key> {
    if (header.algorithm !in Ed25519.ALGORITHMS) return super.selectJWSKeys(header, context)
    val matcher = createJWKMatcher(header) ?: return emptyList()
    return source.get(JWKSelector(matcher), context)
      .filterIsInstance<OctetKeyPair>()
      .mapNotNull(Ed25519::publicKey)
  }
}

/** Nimbus's default factory, plus [JdkEd25519Verifier] for the keys [Ed25519AwareKeySelector] returns. */
internal class Ed25519AwareVerifierFactory : JWSVerifierFactory {

  private val default = DefaultJWSVerifierFactory()

  override fun createJWSVerifier(header: JWSHeader, key: Key): JWSVerifier =
    if (header.algorithm in Ed25519.ALGORITHMS && key is EdECPublicKey) {
      JdkEd25519Verifier(key)
    } else {
      default.createJWSVerifier(header, key)
    }

  override fun supportedJWSAlgorithms(): Set<JWSAlgorithm> = default.supportedJWSAlgorithms() + Ed25519.ALGORITHMS

  override fun getJCAContext(): JCAContext = default.jcaContext
}

/**
 * One Ed25519 signature check. A signature that does not hold answers `false`, which the processor
 * reports as a bad signature; a key the JDK will not take is a [JOSEException], which
 * [JwtVerifier] reads as inconclusive.
 */
internal class JdkEd25519Verifier(private val key: PublicKey) : BaseJWSProvider(Ed25519.ALGORITHMS), JWSVerifier {

  // Nimbus's own verifiers refuse a `crit` header they do not understand (RFC 7515 §4.1.11).
  private val criticalHeaders = CriticalHeaderParamsDeferral()

  override fun verify(header: JWSHeader, signingInput: ByteArray, signature: Base64URL): Boolean {
    if (header.algorithm !in Ed25519.ALGORITHMS) throw JOSEException("Unsupported JWS algorithm ${header.algorithm}")
    if (!criticalHeaders.headerPasses(header)) return false
    return try {
      Signature.getInstance("Ed25519").run {
        initVerify(key)
        update(signingInput)
        verify(signature.decode())
      }
    } catch (e: InvalidKeyException) {
      throw JOSEException("Ed25519 key rejected: ${e.message}", e)
    } catch (_: SignatureException) {
      // A malformed signature, e.g. the wrong length. Not a signature that holds.
      false
    }
  }
}
