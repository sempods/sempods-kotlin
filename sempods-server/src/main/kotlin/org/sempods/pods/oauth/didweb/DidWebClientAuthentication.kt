package org.sempods.pods.oauth.didweb

import com.google.inject.Inject
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.auth.PrivateKeyJWT
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.auth.verifier.ClientAuthenticationVerifier
import com.nimbusds.oauth2.sdk.auth.verifier.ClientCredentialsSelector
import com.nimbusds.oauth2.sdk.auth.verifier.Context
import com.nimbusds.oauth2.sdk.auth.verifier.ExpendedJTIChecker
import com.nimbusds.oauth2.sdk.auth.verifier.InvalidClientException
import com.nimbusds.oauth2.sdk.auth.verifier.JWTAudienceCheck
import com.nimbusds.oauth2.sdk.auth.verifier.PKIClientX509CertificateBindingVerifier
import com.nimbusds.oauth2.sdk.id.Audience
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.JWTID
import org.sempods.auth.core.DidWeb
import java.security.PublicKey
import java.time.Duration
import java.util.Date

/**
 * Whether a JWT was signed by the `did:web` it names, for this pod: a client assertion at the
 * token endpoint (RFC 7523 §2.2, `private_key_jwt`) or a software statement at registration
 * (RFC 7591 §2.3). The checks are the same for both, with a different audience.
 *
 * The SDK's `ClientAuthenticationVerifier` does the protocol: `iss`, `sub` and `aud` present,
 * `iss` equal to `sub`, `aud` one of the audiences given, `exp` in the future and at most
 * [MAX_LIFETIME] ahead, `nbf` passed, and the signature, all with its own clock skew. What it
 * leaves open is this pod's, and checked here first:
 *
 *  - **The issuer is a `did:web`.** The keys are the DID document's — [DidWebKeys].
 *  - **RS256, PS256 or ES256**, named in the header with a `kid`. The SDK accepts every RSA and EC
 *    algorithm and, without a `kid`, would try every key.
 *  - **A `jti`, spent once.** The SDK checks a `jti` only where there is one, and asks whether it
 *    was spent before it writes it down — two requests at once would both pass. Here the asking is
 *    the spending, one atomic insert ([ClientAssertionJtiStore]), and it happens before the
 *    signature is checked: a forged assertion spends a `jti`, which costs the client nothing,
 *    since a `jti` it chose at random is not one anyone else can guess.
 *
 * Every refusal says why, in a sentence the caller can act on; none of them distinguishes a client
 * that exists from one that does not, because the caller named the identifier itself.
 */
class DidWebClientAuthentication @Inject internal constructor(
  private val keys: DidWebKeys,
  private val jtis: ClientAssertionJtiStore,
) {

  /**
   * Checks [jwt] against [audiences] and answers the `did:web` that signed it.
   *
   * @param expectedClientId where given, the identifier the assertion has to be issued by — the
   *   registration the caller is authenticating as.
   */
  internal fun verify(jwt: String, audiences: Set<String>, expectedClientId: String? = null): DidWebAssertion {
    val signed = try {
      SignedJWT.parse(jwt)
    } catch (_: java.text.ParseException) {
      return DidWebAssertion.Refused("the assertion is not a signed JWT")
    }
    if (signed.header.algorithm !in ALGORITHMS) {
      return DidWebAssertion.Refused("the assertion must be signed with RS256, PS256 or ES256")
    }
    if (signed.header.keyID.isNullOrBlank()) {
      return DidWebAssertion.Refused("the assertion's header names no kid")
    }
    val authentication = try {
      PrivateKeyJWT(signed)
    } catch (e: IllegalArgumentException) {
      return DidWebAssertion.Refused("the assertion's claims are incomplete: ${e.message}")
    }
    val clientId = authentication.clientID.value
    if (DidWeb.targetOf(clientId) == null) {
      return DidWebAssertion.Refused("the assertion's subject is not a did:web identifier")
    }
    if (expectedClientId != null && clientId != expectedClientId) {
      return DidWebAssertion.Refused("the assertion is not issued by '$expectedClientId'")
    }
    if (authentication.jwtAuthenticationClaimsSet.jwtid == null) {
      return DidWebAssertion.Refused("the assertion carries no jti")
    }

    val verifier = ClientAuthenticationVerifier(
      selector,
      null as PKIClientX509CertificateBindingVerifier<Unit>?,
      audiences.mapTo(mutableSetOf(), ::Audience),
      JWTAudienceCheck.LEGACY,
      jtiChecker,
      MAX_LIFETIME.seconds,
    )
    return try {
      verifier.verify(authentication, null, Context())
      DidWebAssertion.Verified(clientId, signed.jwtClaimsSet.claims)
    } catch (e: InvalidClientException) {
      DidWebAssertion.Refused(e.message ?: "the assertion does not verify")
    } catch (_: JOSEException) {
      DidWebAssertion.Refused("the assertion's signature cannot be checked against the key its kid names")
    }
  }

  private val selector = object : ClientCredentialsSelector<Unit> {
    override fun selectClientSecrets(clientId: ClientID, method: ClientAuthenticationMethod, context: Context<Unit>): List<Secret> =
      throw InvalidClientException("a did:web client authenticates with private_key_jwt")

    override fun selectPublicKeys(
      clientId: ClientID,
      method: ClientAuthenticationMethod,
      header: JWSHeader,
      forceRefresh: Boolean,
      context: Context<Unit>,
    ): List<PublicKey> = keys.publicKeys(clientId.value, header.keyID).ifEmpty {
      throw InvalidClientException("the DID document of '${clientId.value}' names no authentication key '${header.keyID}'")
    }
  }

  private val jtiChecker = object : ExpendedJTIChecker<Unit> {
    override fun isExpended(jti: JWTID, clientId: ClientID, method: ClientAuthenticationMethod, context: Context<Unit>): Boolean =
      !jtis.claim(clientId.value, jti.value, JTI_RETENTION)

    override fun markExpended(jti: JWTID, exp: Date, clientId: ClientID, method: ClientAuthenticationMethod, context: Context<Unit>) {
      // Spent in [isExpended], atomically.
    }
  }

  companion object {

    /** How far ahead an assertion's `exp` may lie: RFC 7523 §3 asks for a short one. */
    internal val MAX_LIFETIME: Duration = Duration.ofMinutes(5)

    /** How long a spent `jti` is kept: past [MAX_LIFETIME] and the SDK's clock skew of a minute. */
    private val JTI_RETENTION: Duration = MAX_LIFETIME.plusMinutes(2)

    /** The algorithms an assertion may be signed with — what the metadata advertises. */
    internal val ALGORITHMS: List<JWSAlgorithm> = listOf(JWSAlgorithm.RS256, JWSAlgorithm.PS256, JWSAlgorithm.ES256)
  }
}

/** What [DidWebClientAuthentication.verify] answers. */
internal sealed interface DidWebAssertion {

  /**
   * @param claims every claim the JWT carries — a software statement states client metadata in
   *   them (RFC 7591 §2.3).
   */
  data class Verified(val clientId: String, val claims: Map<String, Any?>) : DidWebAssertion

  data class Refused(val reason: String) : DidWebAssertion
}
