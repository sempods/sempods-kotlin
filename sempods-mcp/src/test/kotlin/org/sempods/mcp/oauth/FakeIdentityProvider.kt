package org.sempods.mcp.oauth

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.auth.core.HttpTransport
import org.sempods.mcp.auth.JwtTestSupport
import java.time.Instant
import java.util.Date

/**
 * An id-server that answers over the transport port instead of the network.
 *
 * It serves all three documents a relying party asks for — discovery, JWKS and the token
 * response — because the flow fetches every one of them through [HttpTransport]. The id_token is
 * really signed and really validated: the point of these tests is the round trip, and a fake that
 * could talk its way past the signature check would not exercise it.
 *
 * The nonce is the one thing the fake cannot know on its own — the relying party mints it and
 * keeps it server-side. A test reads it out of the authorization URL and hands it over via
 * [expect], which is exactly what a real provider does with the `nonce` parameter it received.
 */
class FakeIdentityProvider(
  val issuer: String = "https://id.test",
  private val audience: String,
) {

  private val key = JwtTestSupport.generateKey("id-k1")

  /** Set from the authorization URL before the callback is driven. */
  @Volatile private var nonce: String? = null
  @Volatile private var subject: String? = null
  @Volatile private var alsoKnownAs: List<String> = emptyList()

  /** What the next token exchange will assert about the person signing in. */
  fun expect(webId: String, nonce: String, alsoKnownAs: List<String> = emptyList()) = apply {
    this.subject = webId
    this.nonce = nonce
    this.alsoKnownAs = alsoKnownAs
  }

  val transport: HttpTransport = object : HttpTransport {

    override fun get(url: String): String = when (url) {
      "$issuer/.well-known/openid-configuration" -> """
        {"issuer":"$issuer","authorization_endpoint":"$issuer/authorize",
         "token_endpoint":"$issuer/token","jwks_uri":"$issuer/.well-known/jwks.json"}
      """.trimIndent()

      "$issuer/.well-known/jwks.json" -> JWKSet(key.toPublicJWK()).toString()

      else -> error("fake id-server got an unexpected GET: $url")
    }

    override fun postForm(url: String, form: Map<String, String>): String {
      check(url == "$issuer/token") { "fake id-server got an unexpected POST: $url" }
      val webId = subject ?: return """{"error":"invalid_grant","error_description":"no login expected"}"""
      val now = Instant.now()
      val claims = JWTClaimsSet.Builder()
        .issuer(issuer)
        .subject(webId)
        .audience(audience)
        .claim("nonce", nonce)
        .claim("webid", webId)
        .apply { if (alsoKnownAs.isNotEmpty()) claim("also_known_as", alsoKnownAs) }
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .build()
      return """
        {"token_type":"Bearer","access_token":"fake-access-token","expires_in":300,
         "id_token":"${JwtTestSupport.sign(key, claims)}"}
      """.trimIndent()
    }
  }

  /** Wired the way the service wires it, minus the HTTP client. */
  fun identityProvider(serviceBaseUrl: String) =
    IdentityProvider(issuer = issuer, serviceBaseUrl = serviceBaseUrl, transport = transport)
}
