package org.sempods.auth.core

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.BadJWSException
import com.nimbusds.jose.proc.JOSEObjectTypeVerifier
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.nimbusds.jwt.proc.JWTClaimsSetVerifier
import java.io.IOException
import java.net.URI

/**
 * The outcome of [JwtVerifier.verify], three-valued on purpose.
 *
 * The distinction that matters is between *this token is bad* and *we could not tell*, and it is
 * not academic: [Rejected] on a pod's freshly refreshed access token is a tamper signal worth
 * refusing a connection over, while [Inconclusive] on the same token means the pod signs with an
 * algorithm we cannot check or its JWKS was briefly unreachable — refusing there would brick a
 * healthy connection over a network blip. `PodOAuthClient.SubjectOutcome` made this split by hand
 * before this class existed; the other three callers collapse both arms to "no", which costs them
 * nothing.
 */
sealed interface JwtVerification {

  /** Signature held, and so did every claim check [JwtVerifier] was configured with. */
  data class Verified(val claims: JWTClaimsSet) : JwtVerification

  /**
   * A key was available and the token did not hold against it. Definitive: this token is not good.
   *
   * [why] is carried rather than collapsed because the two causes are different events to an
   * operator: an access token aging out is routine and constant, a signature that does not verify
   * is a reason to look. A caller that logged them at different levels — the pod bearer path does —
   * would otherwise have to re-derive the distinction nimbus already made.
   */
  data class Rejected(val why: JwtRejection) : JwtVerification

  /**
   * Verification could not be attempted. Not a JWT at all, no key matching the header's `kid`, no
   * verifier for the token's algorithm, or a key source that could not be read. Says nothing about
   * the token.
   */
  data object Inconclusive : JwtVerification
}

/** Why a token that *could* be checked did not hold. See [JwtVerification.Rejected]. */
enum class JwtRejection {

  /** A key was selected for the header's `kid` and the signature failed against it. */
  badSignature,

  /** The signature held and a claim check did not: expired, or missing a required `exp`. */
  badClaims,
}

/** What [JwtVerifier] checks once the signature holds. */
enum class ClaimChecks {

  /**
   * `exp` must be **present** and in the future. Nimbus validates an expiry it finds but does not
   * insist there is one, so a token minted without `exp` would otherwise verify forever — the same
   * trap [IdTokenVerifier] names. This is what a self-issued token gets: we mint it, so we know it
   * has one.
   */
  requireExpiry,

  /**
   * Signature only. For a token another party issued, whose lifetime is that party's business and
   * whose clock is not ours: reading `sub` off a pod's access token to check it is still the same
   * identity is not an occasion to second-guess the pod about when its own token dies.
   */
  signatureOnly,
}

/**
 * One signed-JWT verifier, over nimbus's `DefaultJWTProcessor`.
 *
 * Replaces four hand-written copies of `SignedJWT.parse` → filter by `kid` → `RSASSAVerifier` in a
 * `runCatching {}.any {}` loop. Two of them checked an incoming bearer (the pod's and the hosted
 * MCP service's), one a browser session cookie, one an outgoing token's subject; all four were
 * RSA-only, and one re-fetched and re-parsed a remote JWKS on every call.
 *
 * **`iss` is deliberately not checked here.** Every caller's expected issuer is different — this
 * pod, this profile, the service root — and one of them binds it to a claim on the token itself.
 * A parameter for it would be a parameter each caller fills with something the others cannot use,
 * and the check reads better at the call site, where the pod or profile is in hand.
 *
 * **Nor is the `typ` header.** None of the four callers ever looked at it, and nimbus's default
 * type verifier would newly reject a third-party pod that stamps its access tokens `at+jwt`
 * (RFC 9068) — turning a working pod into a refusal. Replacing a hand-written check is this
 * class's job; inventing a stricter one is not.
 *
 * Build one per key source and keep it: the local mode holds parsed keys, and the remote mode holds
 * nimbus's self-refreshing view of a JWKS. A fresh instance per call turns both into per-request
 * work, which is the cost this class exists to remove.
 */
class JwtVerifier private constructor(
  private val processor: DefaultJWTProcessor<SecurityContext>,
) {

  /**
   * @param token the compact serialization. `null` or blank is [JwtVerification.Inconclusive] —
   *   there is nothing to verify, which is not the same as a token that failed.
   */
  fun verify(token: String?): JwtVerification {
    val raw = token?.takeIf { it.isNotBlank() } ?: return JwtVerification.Inconclusive
    val jwt = try {
      SignedJWT.parse(raw)
    } catch (_: Exception) {
      // Opaque, truncated, or not a JWS at all. Nothing was checked, so nothing is claimed.
      return JwtVerification.Inconclusive
    }
    return try {
      JwtVerification.Verified(processor.process(jwt, null))
    } catch (_: BadJWSException) {
      // "Signed JWT rejected: Invalid signature" — a key was found and the token failed against it.
      JwtVerification.Rejected(JwtRejection.badSignature)
    } catch (_: BadJWTException) {
      // A claim check failed: expired, or missing an `exp` this verifier requires. Ordered before
      // `BadJOSEException`, which it extends, and after `BadJWSException`, which is a sibling.
      JwtVerification.Rejected(JwtRejection.badClaims)
    } catch (_: BadJOSEException) {
      // The remaining `BadJOSEException`s are both "we could not check": "Another algorithm
      // expected, or no matching key(s) found" and "No matching verifier(s) found". Ordered after
      // its two subclasses above, which are the definitive ones.
      JwtVerification.Inconclusive
    } catch (_: JOSEException) {
      // `KeySourceException` among them: the JWKS could not be fetched or parsed. Transient.
      JwtVerification.Inconclusive
    }
  }

  companion object {

    /**
     * RSA and NIST P-256, both of which nimbus verifies with the JDK alone.
     *
     * TODO: EdDSA is not in here because nimbus's Ed25519 verifier needs Tink on the classpath,
     *  and `sempods-server` has it while `sempods-mcp` does not — so one module would answer
     *  differently in two services, and the one without it would fail with `NoClassDefFoundError`
     *  rather than the [JwtVerification.Inconclusive] this class promises for an algorithm it
     *  cannot check. Add `EdDSA` once Tink is on both, not before.
     */
    val DEFAULT_ALGORITHMS: Set<JWSAlgorithm> = setOf(JWSAlgorithm.RS256, JWSAlgorithm.ES256)

    /**
     * Verify against keys this process already holds — a self-issued token.
     *
     * @param keys typically `SigningKeys.publicKeys`, which are parsed once at boot. Handing them
     *   over rather than a JWKS document is the whole point: two of the call sites this replaces
     *   ran `JWKSet.parse` on every authenticated request.
     */
    fun localKeys(
      keys: List<JWK>,
      claimChecks: ClaimChecks = ClaimChecks.requireExpiry,
      algorithms: Set<JWSAlgorithm> = DEFAULT_ALGORITHMS,
    ): JwtVerifier = JwtVerifier(processor(ImmutableJWKSet(JWKSet(keys)), claimChecks, algorithms))

    /**
     * Verify against a JWKS another party publishes.
     *
     * The fetch goes through [transport] rather than nimbus's default retriever, for the reason
     * [OidcRelyingParty] gives: the caller's HTTP client carries the outbound policy — timeouts,
     * DNS vetting, the SSRF allowlist — and half the traffic riding a library's own client is how
     * that policy stops being one. **Whether [jwksUri] may be dialled at all is still the caller's
     * check**, and it has to happen here, at build time: after this returns, the fetch is nimbus's
     * to schedule.
     *
     * The source caches with nimbus's defaults — a five-minute TTL and rate limiting — so a stable
     * issuer is not re-fetched per verification. `JwtVerifierTest` pins the resulting chain.
     *
     * **Refresh-ahead is off**, because it is the only layer in nimbus's chain that owns an
     * executor at all — `RefreshAheadCachingJWKSetSource` builds an `ExecutorService` and a
     * `ScheduledExecutorService` from `Executors.newSingleThreadExecutor()` and
     * `newSingleThreadScheduledExecutor()`, neither given a thread factory, so any thread they
     * start is **non-daemon** and is reclaimed only by `close()` on the source. Nothing here closes
     * one: a verifier is cached per issuer and replaced when its keys go stale.
     *
     * Measured on nimbus 10.9.1, a default `build()` starts no thread — not at construction, and
     * not after a fetch; the executors are objects nothing has submitted to. So this is a latent
     * edge closed rather than a leak observed, and it is closed because the layer costs more than
     * it returns: refresh-ahead exists so that a request never waits on a fetch, and these
     * verifications are request-driven and roughly hourly, so the cache has almost always expired
     * before the next one anyway. Without the layer the fetch happens on the calling thread —
     * which is where the caller's cancellation and tracing already are, rather than on a private
     * worker that has neither.
     */
    fun remoteJwks(
      jwksUri: String,
      transport: HttpTransport,
      claimChecks: ClaimChecks = ClaimChecks.signatureOnly,
      algorithms: Set<JWSAlgorithm> = DEFAULT_ALGORITHMS,
    ): JwtVerifier {
      val source: JWKSource<SecurityContext> = JWKSourceBuilder
        .create<SecurityContext>(
          URI.create(jwksUri).toURL(),
          // The `IOException` wrapper is load-bearing, not defensive tidying. `ResourceRetriever`
          // declares `IOException`, and that is the only failure nimbus turns into a
          // `JWKSetRetrievalException` — its caching and outage-tolerance layers key off that type.
          // An `HttpTransport` that fails with a runtime exception (`:sempods-client`'s does)
          // otherwise propagates straight through `process`, past every catch below, and out of a
          // method whose whole contract is that it answers rather than throws.
          ResourceRetriever { url ->
            try {
              Resource(transport.get(url.toString()), "application/json")
            } catch (e: Exception) {
              throw IOException("JWKS fetch failed", e)
            }
          },
        )
        // See the KDoc: the only layer that owns an executor. Everything else in the chain —
        // caching, rate limiting, retries, outage tolerance — is lock-based and owns none.
        .refreshAheadCache(false)
        .build()
      return JwtVerifier(processor(source, claimChecks, algorithms))
    }

    private fun processor(
      source: JWKSource<SecurityContext>,
      claimChecks: ClaimChecks,
      algorithms: Set<JWSAlgorithm>,
    ): DefaultJWTProcessor<SecurityContext> = DefaultJWTProcessor<SecurityContext>().apply {
      jwsKeySelector = JWSVerificationKeySelector(algorithms, source)
      // Accept any `typ`, including none. See the class KDoc.
      jwsTypeVerifier = JOSEObjectTypeVerifier { _, _ -> }
      jwtClaimsSetVerifier = when (claimChecks) {
        ClaimChecks.requireExpiry ->
          DefaultJWTClaimsVerifier<SecurityContext>(null, setOf("exp")).apply {
            // Nimbus tolerates 60 s of clock skew by default, and this mode must not. Skew
            // tolerance is for an issuer whose clock is not ours; a self-issued token is verified
            // by the process that minted it, or by a replica beside it on the same NTP. There is
            // no skew to absorb, so the 60 s would be nothing but a minute of extra life granted
            // to every expired bearer — which is not what the four hand-written checks this
            // replaces did, and not a loosening anyone asked for.
            maxClockSkew = 0
          }
        // An explicit no-op rather than leaving the field alone: `DefaultJWTProcessor` ships a
        // default claims verifier that enforces `exp` when it finds one, so "signature only" has
        // to be said out loud or it is not what happens.
        ClaimChecks.signatureOnly -> JWTClaimsSetVerifier { _, _ -> }
      }
    }
  }
}
