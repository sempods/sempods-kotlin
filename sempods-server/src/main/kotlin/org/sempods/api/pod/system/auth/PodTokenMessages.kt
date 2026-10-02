package org.sempods.api.pod.system.auth

import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.RefreshTokenGrant
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import jakarta.ws.rs.core.MultivaluedMap
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.pods.oauth.flows.PodTokenResult

/**
 * Reading a token request (RFC 6749 §4.1.3, §4.4.2, §6).
 *
 * The grammar is the SDK's: `AuthorizationCodeGrant` and `RefreshTokenGrant` decide what each
 * grant's parameters must look like — a `code_verifier` RFC 7636 §4.1 allows, a `redirect_uri`
 * that is a URI — and `ClientSecretBasic` reads HTTP Basic as RFC 6749 §2.3.1 encodes it. A
 * refusal carries the SDK's own sentence. Three rules stay this pod's:
 *
 *  - **A parameter the route reads is sent once** (RFC 6749 §3.1), checked before anything is
 *    read. One it does not read may repeat, since it is ignored anyway.
 *  - **Three grants.** `GrantType` names the grant; the pod parses only those it offers, and
 *    answers every other `unsupported_grant_type`.
 *  - **Client credentials come from HTTP Basic, and only for `client_credentials`.** The other
 *    grants are public clients naming themselves in the form, whatever the header says.
 *
 * Not `TokenRequest.parse`, which carries a profile of its own where these three are: it refuses
 * any repeated parameter, read or not; it reads `client_secret_post` and `client_assertion`
 * credentials from the form, methods this pod does not offer; it turns a Basic header on a code
 * exchange into a confidential client; and it validates `resource` and `authorization_details`,
 * which the pod does not read.
 *
 * What a parameter *means* — whether `client_id` names a client, what `scope` narrows to — is the
 * exchange's, so those two pass on as text.
 */
internal object PodTokenMessages {

  fun read(form: MultivaluedMap<String, String>, authorization: String?): PodTokenRead {
    val repeated = TOKEN_PARAMETERS.filterTo(sortedSetOf()) { (form[it]?.size ?: 0) > 1 }
    if (repeated.isNotEmpty()) {
      return refused(OAuthErrorCode.INVALID_REQUEST, "${repeated.joinToString(", ")} included more than once")
    }

    val params = project(form)
    val grantType = params["grant_type"]?.single()
      // RFC 6749 §5.2: a missing parameter is `invalid_request`, and `grant_type` is no exception.
      ?: return refused(OAuthErrorCode.INVALID_REQUEST, "missing grant_type")

    return try {
      when (GrantType.parse(grantType)) {
        GrantType.AUTHORIZATION_CODE -> AuthorizationCodeGrant.parse(params).let { grant ->
          PodTokenRead.AuthorizationCode(
            code = grant.authorizationCode.value,
            redirectUri = grant.redirectionURI?.toString(),
            clientId = params["client_id"]?.single(),
            codeVerifier = grant.codeVerifier?.value,
          )
        }

        GrantType.REFRESH_TOKEN -> PodTokenRead.Refresh(
          refreshToken = RefreshTokenGrant.parse(params).refreshToken.value,
          clientId = params["client_id"]?.single(),
          scope = params["scope"]?.single(),
        )

        GrantType.CLIENT_CREDENTIALS -> basicCredentials(authorization)?.let { basic ->
          PodTokenRead.ClientCredentials(
            clientId = basic.clientID.value,
            secret = basic.clientSecret.value,
            scope = params["scope"]?.single(),
          )
        } ?: PodTokenRead.Refused(PodTokenResult.ClientAuthenticationRequired("HTTP Basic authentication required"))

        else -> refused(
          OAuthErrorCode.UNSUPPORTED_GRANT_TYPE,
          "only authorization_code, refresh_token and client_credentials are supported",
        )
      }
    } catch (e: ParseException) {
      refused(
        e.errorObject?.code?.let(OAuthErrorCode::of) ?: OAuthErrorCode.INVALID_REQUEST,
        e.errorObject?.description ?: e.message ?: "malformed token request",
      )
    }
  }

  /**
   * The client id an HTTP Basic header names, read the way [read] reads it — so that the budget a
   * request is counted against is the client it then authenticates as.
   */
  fun basicClientId(authorization: String?): String? = basicCredentials(authorization)?.clientID?.value

  /**
   * `null` for anything that is not a Basic pair. The SDK throws more than its `ParseException`
   * here: a blank client id fails the identifier's constructor.
   */
  private fun basicCredentials(authorization: String?): ClientSecretBasic? {
    if (authorization.isNullOrBlank()) return null
    return try {
      ClientSecretBasic.parse(authorization)
    } catch (_: ParseException) {
      null
    } catch (_: RuntimeException) {
      null
    }
  }

  /**
   * The parameters [read] reads, each once. A blank one is absent, and a value is trimmed — except
   * `code_verifier`, whose RFC 7636 alphabet has no whitespace to trim.
   */
  private fun project(form: MultivaluedMap<String, String>): Map<String, List<String>> =
    TOKEN_PARAMETERS.mapNotNull { name ->
      val value = form.getFirst(name)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      name to listOf(if (name == "code_verifier") value else value.trim())
    }.toMap()

  private fun refused(code: OAuthErrorCode, description: String) =
    PodTokenRead.Refused(PodTokenResult.Refused(code, description))

  /** The parameters `/token` reads, across its three grants. */
  private val TOKEN_PARAMETERS = setOf(
    "grant_type", "code", "redirect_uri", "client_id", "code_verifier", "refresh_token", "scope",
  )
}

/** A token request, read or refused. */
internal sealed interface PodTokenRead {

  data class AuthorizationCode(
    val code: String,
    val redirectUri: String?,
    val clientId: String?,
    val codeVerifier: String?,
  ) : PodTokenRead

  data class Refresh(val refreshToken: String, val clientId: String?, val scope: String?) : PodTokenRead

  data class ClientCredentials(val clientId: String, val secret: String, val scope: String?) : PodTokenRead

  /** Not a request the pod can act on — answered here, because syntax is this layer's. */
  data class Refused(val result: PodTokenResult) : PodTokenRead
}
