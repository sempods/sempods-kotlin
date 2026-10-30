package org.sempods.auth.core

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.commons.net.UrlUtil
import java.io.IOException
import java.net.URI
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * An OpenID Provider answering over [HttpTransport] instead of the network: discovery, JWKS and the
 * token endpoint. Its ID Tokens are really signed, so the relying party validates them in full.
 *
 * A test arms a sign-in with [expect] and puts the returned code in the callback it drives. Each
 * code is its own sign-in, so sign-ins in flight at once — concurrent tests included — stay apart.
 * Codes are not consumed: a replayed callback has to be refused by the relying party's own state.
 * The token's audience is the `client_id` the exchange presents.
 *
 * It answers like a real provider until a test changes [advertisesIss] or [reachable]. A test that
 * changes one where others share the provider takes a [variant].
 */
class FakeOidcProvider private constructor(
  val issuer: String,
  private val key: RSAKey,
  private val signIns: ConcurrentHashMap<String, SignIn>,
  private val codes: AtomicLong,
) : HttpTransport {

  constructor(issuer: String) : this(
    issuer = issuer,
    key = RSAKeyGenerator(2048).keyID("fake-oidc-provider").keyUse(KeyUse.SIGNATURE).generate(),
    signIns = ConcurrentHashMap(),
    codes = AtomicLong(),
  )

  private data class SignIn(val webId: String?, val nonce: String, val claims: Map<String, Any?>)

  /** Whether discovery promises `iss` in every authorization response (RFC 9207). */
  @Volatile var advertisesIss: Boolean = true

  /** `false` fails every request with an [IOException], as a provider that is down does. */
  @Volatile var reachable: Boolean = true

  /**
   * What the sign-in redeemed with the returned code asserts about the person.
   *
   * @param webId `null` refuses the exchange with `invalid_grant`.
   * @param nonce the one the relying party sent; [expectFor] reads it from the request.
   * @param claims further ID Token claims, written verbatim, a `null` value included.
   * @return the code to put in the callback, fresh on every call.
   */
  fun expect(webId: String?, nonce: String, claims: Map<String, Any?> = emptyMap()): String {
    val code = "fake-authorization-code-${codes.incrementAndGet()}"
    signIns[code] = SignIn(webId, nonce, claims)
    return code
  }

  /** [expect] for the nonce of the authorization request at [authorizationUrl]. */
  fun expectFor(authorizationUrl: String, webId: String?, claims: Map<String, Any?> = emptyMap()): String =
    expect(webId, nonceOf(authorizationUrl), claims)

  /** A provider with these keys and armed sign-ins, and [advertisesIss] and [reachable] of its own. */
  fun variant(): FakeOidcProvider = FakeOidcProvider(issuer, key, signIns, codes)

  override fun get(url: String): String {
    checkReachable(url)
    return when (url) {
      "$issuer${OpenIdDiscovery.WELL_KNOWN_PATH}" -> """
        {"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
         "token_endpoint":"$issuer/token","jwks_uri":"$issuer/.well-known/jwks.json",
         "authorization_response_iss_parameter_supported":$advertisesIss}
      """.trimIndent()

      "$issuer/.well-known/jwks.json" -> JWKSet(key.toPublicJWK()).toString()

      else -> error("fake OpenID Provider got an unexpected GET: $url")
    }
  }

  override fun postForm(url: String, form: Map<String, String>): String {
    checkReachable(url)
    check(url == "$issuer/token") { "fake OpenID Provider got an unexpected POST: $url" }
    val signIn = signIns[form["code"]]
    val subject = signIn?.webId ?: return """{"error":"invalid_grant","error_description":"no sign-in expected"}"""
    val claims = JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(subject)
      .audience(form["client_id"])
      .claim("nonce", signIn.nonce)
      .claim("webid", subject)
      .apply { signIn.claims.forEach { (name, value) -> claim(name, value) } }
      .issueTime(Date())
      .expirationTime(Date(System.currentTimeMillis() + 300_000))
      .serializeNullClaims(true)
      .build()
    val idToken = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
      .apply { sign(RSASSASigner(key)) }
      .serialize()
    return """{"token_type":"Bearer","access_token":"fake-access-token","expires_in":300,"id_token":"$idToken"}"""
  }

  private fun checkReachable(url: String) {
    if (!reachable) throw IOException("fake OpenID Provider is unreachable: $url")
  }

  private fun nonceOf(authorizationUrl: String): String =
    checkNotNull(UrlUtil.queryParams(URI(authorizationUrl).rawQuery, decodeParams = true)["nonce"]) {
      "no nonce in $authorizationUrl"
    }
}
