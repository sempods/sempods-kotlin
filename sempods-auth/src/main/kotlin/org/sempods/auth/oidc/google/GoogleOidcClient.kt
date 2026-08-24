package org.sempods.auth.oidc.google

import com.nimbusds.jwt.JWTClaimsSet
import org.sempods.auth.core.IdTokenVerifier
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.core.OidcPrompt
import org.sempods.auth.oidc.OidcProviderClient
import org.sempods.auth.oidc.OidcTokenExchange
import java.net.URLEncoder

class GoogleOidcClient(
  private val clientId: String,
  private val clientSecret: String,
  private val loginBaseUrl: String,
  private val tokenExchange: OidcTokenExchange,
) : OidcProviderClient {

  override val name = "google"
  override val displayName = "Google"

  private val idTokenVerifier = IdTokenVerifier(
    jwksUrl = "https://www.googleapis.com/oauth2/v3/certs",
    canonicalIssuer = "https://accounts.google.com",
    expectedAudience = clientId,
    alsoAccepted = setOf("accounts.google.com"),
  )

  private val redirectUri get() = "$loginBaseUrl/login/oidc/google/callback"

  override fun authorizeUrl(state: String, prompt: OidcPrompt?): String = buildString {
    append("https://accounts.google.com/o/oauth2/v2/auth")
    append("?client_id=").append(URLEncoder.encode(clientId, Charsets.UTF_8))
    append("&redirect_uri=").append(URLEncoder.encode(redirectUri, Charsets.UTF_8))
    append("&response_type=code")
    append("&scope=openid%20email%20profile")
    append("&state=").append(URLEncoder.encode(state, Charsets.UTF_8))
    // Google honours both `login` and `select_account`, so a caller demanding re-authentication
    // gets an actual login screen rather than a silent pass on an existing session.
    prompt?.let { append("&prompt=").append(URLEncoder.encode(it.value, Charsets.UTF_8)) }
  }

  override suspend fun handleCallback(code: String, callbackParams: Map<String, String>): OidcClaims {
    val idToken = tokenExchange.exchangeCode(
      tokenEndpoint = "https://oauth2.googleapis.com/token",
      clientId = clientId,
      clientSecret = clientSecret,
      redirectUri = redirectUri,
      code = code,
    )
    return extract(idTokenVerifier.verify(idToken))
  }

  private fun extract(claims: JWTClaimsSet) = OidcClaims(
    iss = claims.issuer,
    sub = claims.subject,
    email = IdTokenVerifier.verifiedEmail(claims),
    displayName = (claims.getClaim("name") as? String)?.takeIf { it.isNotBlank() },
  )
}
