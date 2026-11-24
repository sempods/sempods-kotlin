package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.util.Resource
import com.nimbusds.jose.util.ResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.oauth2.sdk.AuthorizationCode
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.openid.connect.sdk.AuthenticationRequest
import com.nimbusds.openid.connect.sdk.Nonce
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator
import net.minidev.json.JSONObject
import net.minidev.json.parser.JSONParser
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * A relying party: this side asks an OpenID Provider who someone is.
 *
 * The whole flow lives here rather than in each service, because the *order* is what carries the
 * security and getting it right three times is three chances to get it wrong:
 *
 * ```
 * val started = rp.beginAuthorization()
 * // store started.state → (started.codeVerifier, started.nonce, where the user was going)
 * redirect(started.authorizationUrl)
 *
 * // …the callback arrives with ?code=&state=
 * val stored = consumeOnce(state) ?: reject          // a replayed callback must find nothing
 * val identity = rp.completeAuthorization(code, stored.codeVerifier, stored.nonce)
 * ```
 *
 * `state` ties the callback to the request that began it; `nonce` ties the *token* to it. Both
 * fail silently when left out — the login still works, and so does a replayed one — which is why
 * neither is optional in the signatures below. [BrowserSignIn] runs this round trip for a browser,
 * with the store, the browser pin and the `iss` check around it.
 *
 * Transport is a port ([HttpTransport]): the services that use this hold different HTTP clients,
 * and a shared implementation owning one would impose it on the others.
 *
 * Validation is the SDK's `IDTokenValidator`, which checks issuer, audience, nonce, expiry and
 * signature together. It also checks `azp`, which a hand-written verifier tends not to. The keys
 * it needs come from the provider's JWKS — fetched through the same [HttpTransport], so *all*
 * provider traffic rides the caller's own HTTP client and its policy, rather than half of it
 * riding the SDK's default retriever. The URL form is deliberate over handing it a fixed key set:
 * the SDK re-fetches, so a provider that rotates a signing key does not lock every relying party
 * out until it restarts.
 *
 * @param trustsEquivalentIdentities whether this provider may tell the caller which other WebIDs
 *   name the same person (`SPS-OIDC-018`). Trusting a provider for login does not make it that
 *   authority, so the default is `false`: the claim is still checked, and nothing it names reaches
 *   [VerifiedIdentity.equivalentIdentities].
 */
class OidcRelyingParty(
  val metadata: OidcProviderMetadata,
  private val clientId: String,
  /** Where the provider sends the browser back. [BrowserSignIn] completes a sign-in only here. */
  val redirectUri: String,
  private val transport: HttpTransport,
  val trustsEquivalentIdentities: Boolean = false,
) {

  private val validator = IDTokenValidator(
    Issuer(metadata.issuer),
    ClientID(clientId),
    JWSAlgorithm.RS256,
    URL(metadata.jwksUri),
    ResourceRetriever { url -> Resource(transport.get(url.toString()), "application/json") },
  )

  /**
   * Whether an authorization response naming [iss] comes from this provider (RFC 9207 §2.4):
   * the same string, or none where the provider does not promise one. Checked before anything
   * else in the response is believed, an error included.
   */
  fun acceptsResponseIssuer(iss: String?): Boolean =
    iss?.let { it == metadata.issuer } ?: !metadata.authorizationResponseIssParameterSupported

  /** What a caller has to keep until the callback comes back. */
  data class Started(
    val authorizationUrl: String,
    val state: String,
    val nonce: String,
    /** Never leaves the server. Sending it in the front channel would defeat PKCE entirely. */
    val codeVerifier: String,
  )

  /**
   * Who the provider says this is. SDK types stay inside; a caller gets what it needs.
   *
   * The four person claims (OIDC Core §5.1) are read from the validated token, whatever scope the
   * request named. A provider releases them for their scope (`profile` for [name] and [picture],
   * `email` for [email] and [emailVerified]), may leave any of them out, and may send them unasked.
   * Each is `null` when absent, and also when it has the wrong shape, which leaves the sign-in
   * itself standing: they are for display, and a malformed one is no reason to lock the person out.
   *
   * | Claim | Value | `null` when |
   * |---|---|---|
   * | `name` | the string, trimmed | not a string, or blank |
   * | `email` | the string, trimmed | not a string, or blank |
   * | `email_verified` | `true` or `false`, from a JSON boolean or the string `"true"` or `"false"` in any case | anything else |
   * | `picture` | the string | not an absolute `http` or `https` URL with a host |
   *
   * @property equivalentIdentities the WebIDs the provider asserts for the same person
   *   ([EquivalentIdentities.read]). Empty when the provider asserted none, and always empty unless
   *   [trustsEquivalentIdentities].
   * @property name the person's full name, for display. Not an identifier: [webId] is.
   * @property email an address the provider names for the person, unverified unless
   *   [emailVerified] is `true`. Not an identifier either: an address can move between people.
   * @property emailVerified whether the provider checked that [email] belongs to the person.
   * @property picture a URL the provider gives for the person's picture. Untrusted: it points
   *   wherever the provider's data says, so a caller that loads it lets that host see the viewer.
   *   Escape it as any other attribute value.
   */
  data class VerifiedIdentity @JvmOverloads constructor(
    val webId: String,
    val equivalentIdentities: Set<String>,
    val issuer: String,
    val name: String? = null,
    val email: String? = null,
    val emailVerified: Boolean? = null,
    val picture: String? = null,
  )

  /**
   * @param state supply one when the caller already mints an unpredictable, one-time value it
   *   stores the pending flow under — the alternative is two states for one round trip, and two
   *   chances for them to disagree about which request a callback belongs to.
   */
  fun beginAuthorization(
    scopes: Set<String> = setOf(OidcProviderMetadata.OPENID_SCOPE),
    prompt: String? = null,
    state: String? = null,
  ): Started {
    val requestState = state?.let { State(it) } ?: State()
    val nonce = Nonce()
    val verifier = CodeVerifier()

    val builder = AuthenticationRequest.Builder(
      ResponseType.CODE,
      Scope(*scopes.toTypedArray()),
      ClientID(clientId),
      URI(redirectUri),
    )
      .endpointURI(URI(metadata.authorizationEndpoint))
      .state(requestState)
      .nonce(nonce)
      .codeChallenge(verifier, CodeChallengeMethod.S256)

    prompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
      // Unparseable prompts are dropped rather than passed on: this value often originates in a
      // request from further upstream, and forwarding whatever arrived would hand it to the
      // provider unchecked.
      runCatching { builder.prompt(com.nimbusds.openid.connect.sdk.Prompt.parse(it)) }
    }

    return Started(
      authorizationUrl = builder.build().toURI().toString(),
      state = requestState.value,
      nonce = nonce.value,
      codeVerifier = verifier.value,
    )
  }

  /**
   * Exchanges the code and validates what comes back.
   *
   * @param expectedNonce the value stored alongside [codeVerifier] when the flow began. Required
   *   rather than optional: without it a token minted for an earlier login of the same person at
   *   the same client — still signed, still unexpired — passes (OIDC Core §3.1.3.7).
   * @throws IllegalStateException when the provider returned an error, the response carries no
   *   `id_token`, or the token's equivalent-identity claim is malformed ([EquivalentIdentities.read]).
   *   The provider's own reason is carried into the message: it is the only thing that says *why*,
   *   and losing it turns a diagnosable failure into "login did not work".
   * @throws Exception when the token does not validate.
   */
  fun completeAuthorization(code: String, codeVerifier: String, expectedNonce: String): VerifiedIdentity {
    val body = transport.postForm(
      metadata.tokenEndpoint,
      linkedMapOf(
        "grant_type" to "authorization_code",
        "code" to AuthorizationCode(code).value,
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        // The half that never travelled through the browser. Presenting it is what proves this is
        // the same party that started the flow.
        "code_verifier" to CodeVerifier(codeVerifier).value,
      ),
    )

    val parsed = OIDCTokenResponseParser.parse(body.toJsonObject())
    if (!parsed.indicatesSuccess()) {
      val error = parsed.toErrorResponse().errorObject
      error("token endpoint returned ${error.code}${error.description?.let { ": $it" }.orEmpty()}")
    }
    val idToken = (parsed.toSuccessResponse() as OIDCTokenResponse).oidcTokens.idToken
      ?: error("token response carries no id_token")

    val claims = validator.validate(idToken, Nonce(expectedNonce))
    // Read from the token itself. The SDK's claims set drops a claim whose value is `null`, which
    // would make a value that must be refused look absent. Read whether or not this provider is
    // trusted with it, because a malformed claim refuses the login either way.
    val token = idToken.jwtClaimsSet
    val equivalentIdentities = EquivalentIdentities.read(token)
    return VerifiedIdentity(
      webId = claims.subject.value,
      equivalentIdentities = if (trustsEquivalentIdentities) equivalentIdentities else emptySet(),
      issuer = claims.issuer.value,
      name = token.text("name"),
      email = token.text("email"),
      emailVerified = when (val raw = token.getClaim("email_verified")) {
        // Apple sends the string spelling in some responses ([IdTokenVerifier.isTrue]).
        is Boolean -> raw
        is String -> raw.lowercase().toBooleanStrictOrNull()
        else -> null
      },
      picture = (token.getClaim("picture") as? String)?.takeIf(::isHttpUrl),
    )
  }

  companion object {

    /** Reads the provider's metadata. One call at wiring time, not one per login. */
    fun discover(
      issuer: String,
      clientId: String,
      redirectUri: String,
      transport: HttpTransport,
      trustsEquivalentIdentities: Boolean = false,
    ): OidcRelyingParty {
      val document = transport.get(OidcProviderMetadata.discoveryUrl(issuer))
      val metadata = OidcProviderMetadata.parse(document)
      check(metadata.issuer == issuer) {
        // A provider advertising one issuer and signing another is misconfigured, and the tokens
        // would be rejected one by one at verification with nothing pointing here.
        "provider at $issuer advertises issuer '${metadata.issuer}'"
      }
      return OidcRelyingParty(metadata, clientId, redirectUri, transport, trustsEquivalentIdentities)
    }

    private fun JWTClaimsSet.text(name: String): String? =
      (getClaim(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isHttpUrl(value: String): Boolean {
      val uri = try {
        URI(value)
      } catch (_: URISyntaxException) {
        return false
      }
      return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
    }

    private fun String.toJsonObject(): JSONObject =
      runCatching { JSONParser(JSONParser.MODE_PERMISSIVE).parse(this) as JSONObject }
        .getOrElse { throw IllegalStateException("token response is not a JSON object: ${it.message}") }
  }
}
