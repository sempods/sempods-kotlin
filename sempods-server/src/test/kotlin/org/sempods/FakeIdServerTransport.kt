package org.sempods

import com.google.inject.Inject
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.sempods.auth.core.HttpTransport
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The id-server, answering over the transport port instead of the network.
 *
 * It serves all three documents a relying party asks for — discovery, JWKS and the token response
 * — because the flow fetches every one of them through [HttpTransport]. The id_token is really
 * signed and really validated: what these tests exercise is the round trip, and a fake that could
 * talk its way past the signature check would not exercise it.
 *
 * The nonce is the one thing the fake cannot know on its own — the relying party mints it and
 * keeps it server-side. A test reads it out of the authorization URL and hands it over via
 * [expect], which is exactly what a real provider does with the `nonce` parameter it received.
 */
class FakeIdServerTransport @Inject constructor() : HttpTransport {

  private val key: RSAKey = RSAKeyGenerator(2048).keyID("id-key-1").keyUse(KeyUse.SIGNATURE).generate()

  /** What one armed sign-in will assert, once its code is redeemed. */
  private data class Login(val webId: String?, val nonce: String, val alsoKnownAs: List<String>)

  /**
   * One entry per armed sign-in, keyed by the authorization code that redeems it — which is what
   * a real provider remembers too, and the only thing the token endpoint is told.
   *
   * A single slot would be shared state. One injector serves the whole suite, so with
   * `-PtestMethodsConcurrent` a second [expect] lands between the first test's [expect] and its
   * callback, and that test's token exchange comes back carrying somebody else's nonce — a login
   * that fails for a reason the test never asked about.
   *
   * Entries are not consumed: a replayed callback must be refused by the pod's own one-time state
   * store, and a fake that forgot the code first would answer for it.
   */
  private val logins = ConcurrentHashMap<String, Login>()

  private val codes = AtomicLong()

  /**
   * What the sign-in redeemed with the returned code will assert about the person signing in.
   *
   * @param webId `null` makes the id-server refuse the exchange, which is how a test reaches the
   *   pod server's "login failed" branch without having to forge anything.
   * @return the authorization code this login is armed for. Put it in the callback the test
   *   drives; it is fresh on every call, which is what keeps two sign-ins in flight apart.
   */
  fun expect(webId: String?, nonce: String, alsoKnownAs: List<String> = emptyList()): String {
    val code = "fake-authorization-code-${codes.incrementAndGet()}"
    logins[code] = Login(webId = webId, nonce = nonce, alsoKnownAs = alsoKnownAs)
    return code
  }

  override fun get(url: String): String = when (url) {
    "$ISSUER/.well-known/openid-configuration" -> """
      {"issuer":"$ISSUER","authorization_endpoint":"$ISSUER/authorize",
       "token_endpoint":"$ISSUER/token","jwks_uri":"$ISSUER/.well-known/jwks.json"}
    """.trimIndent()

    "$ISSUER/.well-known/jwks.json" -> JWKSet(key.toPublicJWK()).toString()

    else -> error("fake id-server got an unexpected GET: $url")
  }

  override fun postForm(url: String, form: Map<String, String>): String {
    check(url == "$ISSUER/token") { "fake id-server got an unexpected POST: $url" }
    val login = logins[form["code"]]
    val subject = login?.webId ?: return """{"error":"invalid_grant","error_description":"no login expected"}"""
    val claims = JWTClaimsSet.Builder()
      .issuer(ISSUER)
      .subject(subject)
      // Whom the token is for. `did:web:` of the pod server's own origin — the relying party
      // refuses one minted for anybody else, which is half of why this fake has to be told.
      .audience(form["client_id"])
      .claim("nonce", login.nonce)
      .claim("webid", subject)
      .apply { if (login.alsoKnownAs.isNotEmpty()) claim("also_known_as", login.alsoKnownAs) }
      .issueTime(Date())
      .expirationTime(Date(System.currentTimeMillis() + 300_000))
      .build()
    val idToken = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
      .apply { sign(RSASSASigner(key)) }
      .serialize()
    return """{"token_type":"Bearer","access_token":"fake-access-token","expires_in":300,"id_token":"$idToken"}"""
  }

  companion object {
    /**
     * The identity issuer these tests run against. `SempodsTestModule` binds `idBaseUrl` to it,
     * and the relying party refuses a provider advertising anything else — so the two are one
     * constant, here, where the fake provider lives.
     */
    const val ISSUER = "http://test-sempods-auth"
  }
}
