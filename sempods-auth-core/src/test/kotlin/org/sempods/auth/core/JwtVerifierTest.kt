package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.JWKSetBasedJWKSource
import com.nimbusds.jose.jwk.source.JWKSetSourceWrapper
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * The four hand-written verifiers this class replaces all answered `true` or `false`, and each
 * derived the third state — *we could not check* — by hand or not at all. The tests below are
 * mostly about that boundary, because it is the one that decides whether a pod whose JWKS blipped
 * gets disconnected or waits.
 *
 * The remote mode runs against a stub [HttpTransport] rather than a local HTTP server: what is
 * worth pinning is that the fetch goes through the caller's transport at all — that is the SSRF
 * policy — and a stub says so more directly than a socket does.
 */
class JwtVerifierTest {

  private val key = RSAKeyGenerator(2048).keyID("k1").keyUse(KeyUse.SIGNATURE)
    .algorithm(JWSAlgorithm.RS256).generate()

  private fun token(
    signWith: RSAKey = key,
    kid: String? = signWith.keyID,
    expiresAt: Date? = Date(System.currentTimeMillis() + 600_000),
    type: JOSEObjectType? = null,
    subject: String = "user-1",
  ): String {
    val claims = JWTClaimsSet.Builder().subject(subject)
      .apply { expiresAt?.let { expirationTime(it) } }
      .build()
    val header = JWSHeader.Builder(JWSAlgorithm.RS256)
      .apply { kid?.let { keyID(it) }; type?.let { type(it) } }
      .build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(signWith)) }.serialize()
  }

  private fun local(claimChecks: ClaimChecks = ClaimChecks.requireExpiry) =
    JwtVerifier.localKeys(listOf(key.toPublicJWK()), claimChecks)

  // ─── Local keys ───────────────────────────────────────────────────────────

  @Test
  fun `a token signed by a known key verifies, and the claims come back`() {
    val result = local().verify(token())

    assertIs<JwtVerification.Verified>(result)
    assertEquals("user-1", result.claims.subject)
  }

  @Test
  fun `a token signed by a stranger is definitively rejected`() {
    // The `kid` matches ours, so a key IS selected and the signature genuinely fails. That is the
    // difference between this and the no-matching-key case below, and the two must not collapse.
    val foreign = RSAKeyGenerator(2048).keyID(key.keyID).algorithm(JWSAlgorithm.RS256).generate()

    assertEquals(
      JwtVerification.Rejected(JwtRejection.badSignature),
      local().verify(token(signWith = foreign)),
    )
  }

  @Test
  fun `a kid naming a key we do not have is inconclusive, not a rejection`() {
    val other = RSAKeyGenerator(2048).keyID("k2").algorithm(JWSAlgorithm.RS256).generate()

    assertEquals(JwtVerification.Inconclusive, local().verify(token(signWith = other, kid = "k2")))
  }

  @Test
  fun `a header without a kid is checked against every key`() {
    // What the hand-written loops did when the header carried no `kid`: try them all. Nimbus's
    // matcher does the same, and losing it would silently reject a correctly signed token.
    assertIs<JwtVerification.Verified>(local().verify(token(kid = null)))
  }

  @Test
  fun `an expired token is rejected, and so is one that never expires`() {
    assertEquals(
      JwtVerification.Rejected(JwtRejection.badClaims),
      local().verify(token(expiresAt = Date(System.currentTimeMillis() - 600_000))),
    )
    // Nimbus validates an `exp` it finds but does not insist there is one. Without naming it
    // required, a token minted without an expiry would verify for the life of the key.
    assertEquals(JwtVerification.Rejected(JwtRejection.badClaims), local().verify(token(expiresAt = null)))
  }

  @Test
  fun `expiry is judged without a clock-skew grace`() {
    // Nimbus's 60 s default would keep every expired self-issued bearer alive for another minute.
    // A second past `exp` is expired, which is what the checks this class replaces all said.
    assertEquals(
      JwtVerification.Rejected(JwtRejection.badClaims),
      local().verify(token(expiresAt = Date(System.currentTimeMillis() - 1_000))),
    )
  }

  @Test
  fun `signatureOnly does not judge the expiry`() {
    // The pod-token path: another party's clock and another party's lifetime. Both an absent and a
    // long-past `exp` must still read as verified, or a refresh gets refused over a clock.
    val verifier = local(ClaimChecks.signatureOnly)

    assertIs<JwtVerification.Verified>(verifier.verify(token(expiresAt = null)))
    assertIs<JwtVerification.Verified>(
      verifier.verify(token(expiresAt = Date(System.currentTimeMillis() - 600_000))),
    )
  }

  @Test
  fun `a foreign typ header does not turn a good token into a refusal`() {
    // RFC 9068 access tokens carry `at+jwt`, which nimbus's default type verifier refuses. None of
    // the callers this replaces ever read the header, so refusing here would be new strictness
    // applied to third-party pods.
    assertIs<JwtVerification.Verified>(local().verify(token(type = JOSEObjectType("at+jwt"))))
  }

  @Test
  fun `ES256 verifies against an EC key`() {
    val ec: ECKey = ECKeyGenerator(Curve.P_256).keyID("e1").algorithm(JWSAlgorithm.ES256).generate()
    val jwt = SignedJWT(
      JWSHeader.Builder(JWSAlgorithm.ES256).keyID("e1").build(),
      JWTClaimsSet.Builder().subject("user-1")
        .expirationTime(Date(System.currentTimeMillis() + 600_000)).build(),
    ).apply { sign(ECDSASigner(ec)) }

    assertIs<JwtVerification.Verified>(
      JwtVerifier.localKeys(listOf(ec.toPublicJWK())).verify(jwt.serialize()),
    )
  }

  @Test
  fun `an algorithm outside the allowed set is inconclusive rather than rejected`() {
    // The EdDSA case in miniature: an algorithm this verifier cannot check says nothing about the
    // token. Rejecting would brick an issuer that signs with something we simply do not do yet.
    val verifier = JwtVerifier.localKeys(listOf(key.toPublicJWK()), algorithms = setOf(JWSAlgorithm.ES256))

    assertEquals(JwtVerification.Inconclusive, verifier.verify(token()))
  }

  @Test
  fun `anything that is not a JWT is inconclusive`() {
    val verifier = local()

    assertEquals(JwtVerification.Inconclusive, verifier.verify(null))
    assertEquals(JwtVerification.Inconclusive, verifier.verify("  "))
    assertEquals(JwtVerification.Inconclusive, verifier.verify("an-opaque-token"))
    assertEquals(JwtVerification.Inconclusive, verifier.verify("a.b.c"))
  }

  // ─── Remote JWKS ──────────────────────────────────────────────────────────

  private class StubTransport(private val body: () -> String) : HttpTransport {
    var gets = 0
    override fun get(url: String): String {
      gets++
      return body()
    }
    override fun postForm(url: String, form: Map<String, String>): String = error("not used")
  }

  @Test
  fun `the remote mode fetches the JWKS through the caller's transport`() {
    val transport = StubTransport { JWKSet(key.toPublicJWK()).toString() }

    val result = JwtVerifier.remoteJwks("https://pod.example.invalid/jwks", transport).verify(token())

    assertIs<JwtVerification.Verified>(result)
    assertEquals(1, transport.gets, "the fetch must not ride nimbus's own retriever")
  }

  @Test
  fun `the remote mode caches, so a stable issuer is not re-fetched per verification`() {
    // The whole of what the `PodTokenProvider` TODO asked for: one fetch, not one per refresh.
    val transport = StubTransport { JWKSet(key.toPublicJWK()).toString() }
    val verifier = JwtVerifier.remoteJwks("https://pod.example.invalid/jwks", transport)

    repeat(5) { assertIs<JwtVerification.Verified>(verifier.verify(token())) }

    assertEquals(1, transport.gets)
  }

  @Test
  fun `the remote chain carries no executor-owning layer`() {
    // `RefreshAheadCachingJWKSetSource` is the only layer in nimbus's chain that builds an
    // executor, and any thread it starts is non-daemon and reclaimed only by `close()` — which
    // nothing here calls, since a verifier is cached per issuer and replaced when its keys go
    // stale. A default `build()` was measured to start no thread, so this pins a construction
    // choice rather than an observed leak: the layer must stay out of the chain, and asserting on
    // the chain is deterministic where asserting on threads is not.
    val transport = StubTransport { JWKSet(key.toPublicJWK()).toString() }
    val verifier = JwtVerifier.remoteJwks("https://pod.example.invalid/jwks", transport)

    val processor = JwtVerifier::class.java.getDeclaredField("processor")
      .apply { isAccessible = true }
      .get(verifier) as DefaultJWTProcessor<*>
    val selector = processor.jwsKeySelector as JWSVerificationKeySelector<*>
    var source: Any? = (selector.jwkSource as JWKSetBasedJWKSource<*>).jwkSetSource
    val layers = buildList {
      while (source != null) {
        add(source!!::class.java.simpleName)
        source = (source as? JWKSetSourceWrapper<*>)?.source
      }
    }

    // Asserted whole rather than by absence: the chain is the thing being chosen, and a nimbus
    // upgrade that inserts a layer should be read before it is accepted.
    assertEquals(
      listOf("CachingJWKSetSource", "RateLimitedJWKSetSource", "URLBasedJWKSetSource"),
      layers,
    )
  }

  @Test
  fun `an unfetchable JWKS is inconclusive, and a fetched one that disagrees is a rejection`() {
    // The distinction the pod-connect path is built on: a network blip must not look like tampering.
    val failing = StubTransport { error("connection refused") }
    assertEquals(
      JwtVerification.Inconclusive,
      JwtVerifier.remoteJwks("https://pod.example.invalid/jwks", failing).verify(token()),
    )

    val stranger = RSAKeyGenerator(2048).keyID(key.keyID).algorithm(JWSAlgorithm.RS256).generate()
    val wrongKeys = StubTransport { JWKSet(stranger.toPublicJWK()).toString() }
    assertEquals(
      JwtVerification.Rejected(JwtRejection.badSignature),
      JwtVerifier.remoteJwks("https://pod.example.invalid/jwks", wrongKeys).verify(token()),
    )
  }
}
