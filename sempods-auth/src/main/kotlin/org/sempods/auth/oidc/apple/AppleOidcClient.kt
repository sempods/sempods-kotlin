package org.sempods.auth.oidc.apple

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.auth.core.IdTokenVerifier
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.oidc.OidcProviderClient
import org.sempods.auth.oidc.OidcTokenExchange
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Apple OIDC client.
 *
 * Framework-free, and without a BouncyCastle dependency.
 * Apple's p8 key files are PKCS#8 encoded — readable with standard Java security APIs.
 *
 * Apple uses response_mode=form_post for web flows, so the callback is a POST request.
 * The redirect_uri must be pre-registered in Apple's developer portal, byte for byte.
 *
 * [applePrivateKeyPem]: the .p8 content, passed via `APPLE_PRIVATE_KEY_PEM`. Written on a single
 *   line — neither Docker Compose's `env_file` parser nor `Env.loadFile` carries a multi-line
 *   value — which [loadPrivateKey] handles by stripping the markers and any separators.
 */
class AppleOidcClient(
  private val teamId: String,
  private val serviceId: String,
  private val keyId: String,
  applePrivateKeyPem: String,
  private val loginBaseUrl: String,
  private val tokenExchange: OidcTokenExchange,
) : OidcProviderClient {

  override val name = "apple"
  override val displayName = "Apple"

  private val idTokenVerifier = IdTokenVerifier(
    jwksUrl = "https://appleid.apple.com/auth/keys",
    canonicalIssuer = ISSUER,
    expectedAudience = serviceId,
  )

  // Parsed once, on first use rather than at construction: a malformed key then surfaces on the
  // first Apple login instead of taking down a service whose Google login is fine.
  private val privateKey: ECPrivateKey by lazy { loadPrivateKey(applePrivateKeyPem) }

  private val redirectUri get() = "$loginBaseUrl/login/oidc/apple/callback"

  // TODO: Apple's authorize endpoint does not document `prompt`, so a caller asking for forced
  //  re-authentication cannot be served here — an existing Apple session satisfies the flow. The
  //  parameter is sent anyway (Apple ignores unknown ones) so this starts working if Apple adds
  //  support. Until then a pod that genuinely requires fresh authentication cannot rely on Apple;
  //  making that visible to the caller needs a capability signal through the bridge.
  override fun authorizeUrl(state: String, prompt: OidcPrompt?): String = buildString {
    append("https://appleid.apple.com/auth/authorize")
    append("?client_id=").append(URLEncoder.encode(serviceId, Charsets.UTF_8))
    append("&redirect_uri=").append(URLEncoder.encode(redirectUri, Charsets.UTF_8))
    append("&response_type=code")
    // Requesting `name` or `email` obliges Apple to use form_post, so the two belong together.
    append("&scope=name%20email")
    append("&response_mode=form_post")
    append("&state=").append(URLEncoder.encode(state, Charsets.UTF_8))
    prompt?.let { append("&prompt=").append(URLEncoder.encode(it.value, Charsets.UTF_8)) }
  }

  override suspend fun handleCallback(code: String, callbackParams: Map<String, String>): OidcClaims {
    val idToken = tokenExchange.exchangeCode(
      tokenEndpoint = "https://appleid.apple.com/auth/token",
      clientId = serviceId,
      clientSecret = generateClientSecret(),
      redirectUri = redirectUri,
      code = code,
    )
    return extract(idTokenVerifier.verify(idToken), AppleUserField.parse(callbackParams["user"]))
  }

  private fun extract(claims: JWTClaimsSet, user: AppleUserField): OidcClaims {
    // The address comes from the signed token and from nowhere else. `user` arrives in the callback
    // body, which the browser controls: falling back to it would let anyone who completes an Apple
    // login with their own account POST `user={"email":"someone@else"}` and receive a JWT whose
    // `sub` is that person's `e/<sha256(email)>` WebID — grants and all. `user` is display data.
    val email = IdTokenVerifier.verifiedEmail(claims)

    // TODO: with "Hide My Email" the address is a per-app relay alias, so the derived
    //  `e/<sha256(email)>` WebID differs from the one a pod owner granted against the person's real
    //  address and the grant does not match. Resolving it needs the identity merge described in
    //  `sempods-auth/docs/identity-service.md` — until then, logged so the cause is visible when a
    //  user reports that their invitation did nothing.
    if (IdTokenVerifier.isTrue(claims.getClaim("is_private_email"))) {
      logger.info {
        "Apple login used a private relay address — grants made against the user's real address " +
            "will not match this WebID (sub=${claims.subject})"
      }
    }

    return OidcClaims(
      iss = claims.issuer,
      sub = claims.subject,
      email = email,
      displayName = user.displayName,
    )
  }

  /**
   * Apple's client secret is a short-lived ES256 JWT rather than a stored string.
   *
   * Minted per token exchange, valid for minutes. Caching it would buy nothing — signing costs
   * well under a millisecond and happens once per login — while introducing an expiry: the
   * previous version signed one secret per process with a 30-day lifetime, so a container running
   * longer than that lost Apple login with no signal beyond failing sign-ins.
   */
  internal fun generateClientSecret(): String {
    val header = JWSHeader.Builder(JWSAlgorithm.ES256)
      .keyID(keyId)
      .type(JOSEObjectType.JWT)
      .build()
    val now = Instant.now()
    val claimsSet = JWTClaimsSet.Builder()
      .issuer(teamId)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(now.plusSeconds(CLIENT_SECRET_TTL_SECONDS)))
      .audience("https://appleid.apple.com")
      .subject(serviceId)
      .build()
    return SignedJWT(header, claimsSet).apply { sign(ECDSASigner(privateKey)) }.serialize()
  }

  /**
   * The user's **name**, as Apple sends it in the `user` form field.
   *
   * Apple delivers this **once**, alongside the very first authorization for a given Service ID,
   * and never again — so a login that ignores it leaves the profile permanently nameless.
   *
   * The field also carries an `email`, which is deliberately not parsed here. It is unsigned data
   * from the callback body, and everything this service derives from an address — the WebID, and
   * therefore every grant that matches it — has to come from the signed `id_token`. Not modelling
   * the value at all is what keeps a later change from reaching for it.
   *
   * Malformed input yields an empty instance rather than an exception: the name is a nicety, and
   * failing the whole login over it would trade a missing display name for no login at all.
   */
  internal data class AppleUserField(
    val firstName: String? = null,
    val lastName: String? = null,
  ) {

    val displayName: String?
      get() = listOfNotNull(firstName, lastName).joinToString(" ").takeIf { it.isNotBlank() }

    companion object {

      val EMPTY = AppleUserField()

      fun parse(raw: String?): AppleUserField {
        val json = raw?.takeIf { it.isNotBlank() } ?: return EMPTY
        return runCatching {
          val parsed = JSONObjectUtils.parse(json)
          val name = parsed["name"] as? Map<*, *>
          AppleUserField(
            firstName = (name?.get("firstName") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            lastName = (name?.get("lastName") as? String)?.trim()?.takeIf { it.isNotEmpty() },
          )
        }.getOrElse {
          // Not the token — this field is unsigned and only carries display data.
          logger.warn { "Ignoring unparseable Apple `user` field: ${it.message}" }
          EMPTY
        }
      }
    }
  }

  private companion object {

    private val logger = KotlinLogging.logger {}

    private const val ISSUER = "https://appleid.apple.com"

    /** Apple allows up to six months; minutes are enough for a secret minted per exchange. */
    private const val CLIENT_SECRET_TTL_SECONDS = 300L

    /**
     * Accepts the p8 as downloaded and as a single line.
     *
     * Everything between the markers is base64, and base64 has no significant whitespace, so
     * stripping the markers plus any separator leaves the same key either way. The literal
     * two-character `\n` is stripped too: it is what a `.env` file ends up holding when someone
     * escapes the newlines instead of joining the lines, and it would otherwise corrupt the base64
     * with an error message that points nowhere near the cause.
     */
    private fun loadPrivateKey(pem: String): ECPrivateKey {
      val base64 = pem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\n", "")
        .replace(Regex("\\s"), "")
      val keyBytes = try {
        Base64.getDecoder().decode(base64)
      } catch (e: IllegalArgumentException) {
        // Never the value itself — this is a private key and this is a log line.
        throw IllegalStateException("APPLE_PRIVATE_KEY_PEM is not valid base64 PKCS#8", e)
      }
      return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes)) as ECPrivateKey
    }
  }
}
