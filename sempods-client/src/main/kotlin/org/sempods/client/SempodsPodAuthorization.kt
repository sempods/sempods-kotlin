package org.sempods.client

import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse
import com.nimbusds.oauth2.sdk.AuthorizationRequest
import com.nimbusds.oauth2.sdk.AuthorizationResponse
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.Scope
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.Issuer
import com.nimbusds.oauth2.sdk.id.State
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier
import com.nimbusds.oauth2.sdk.util.URLUtils
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

/**
 * The browser half of a pod's OAuth for a public client: registering one, and the authorization
 * request its user is sent to (Authorization Code + PKCE, RFC 6749 §4.1 and RFC 7636). The code comes
 * back to the caller's redirect, [SempodsAuthorizationRedirect] reads it there, and
 * [SempodsPodTokens.authorizationCode] redeems it.
 *
 * ```java
 * var authorization = new SempodsPodAuthorization(new SempodsSession(alice), client);
 * String clientId = authorization.registerClient("Notes installer", List.of(redirectUri)).getBody().getClientId();
 * SempodsPkce pkce = SempodsPkce.generate();
 * HttpUrl consent = authorization.authorizationUrl(clientId, redirectUri, "service-clients:install", state, pkce);
 * ```
 *
 * **Built on a session of its own**, anonymous as a rule: a public client holds no credential, and
 * nothing here needs one. A redirect to a loopback address is registered once and answers on any port
 * (RFC 8252 §7.3).
 *
 * [SempodsPodServiceClients] has the installation this leads to.
 */
class SempodsPodAuthorization(
  val session: SempodsSession,
  val calls: Call.Factory,
) {

  private val exchange = Exchange(calls)

  /**
   * Registers a public client at `POST {pod}/_system/auth/register` (RFC 7591), with
   * `token_endpoint_auth_method: none` and the `authorization_code` grant.
   *
   * **Safe to repeat**, and sent again after a lost connection: a pod answers the same metadata with
   * the client it registered before. Each call still counts against the pod's registration budget,
   * so a caller keeps the identifier rather than registering on every start.
   *
   * | The pod answers | The caller gets |
   * |---|---|
   * | any 2xx | the client |
   * | `400 invalid_redirect_uri` or `invalid_client_metadata`, `429 slow_down` and every other status | a [SempodsStatusException] whose `bodyExcerpt` holds the RFC 7591 §3.2.2 error document |
   *
   * @throws IllegalArgumentException when a redirect URI is not a URI, or its query carries a member of
   *   the authorization answer, before anything is sent.
   */
  @Throws(IOException::class)
  fun registerClient(clientName: String, redirectUris: List<String>): SempodsResponse<SempodsPublicClient> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, PUBLIC_CLIENT)

  /** The same answer with the body as the text the server sent, malformed or not. */
  @Throws(IOException::class)
  fun registerClientJson(clientName: String, redirectUris: List<String>): SempodsResponse<String> =
    exchange.run(registration(clientName, redirectUris), ANSWERS, BodyReading.TEXT)

  /**
   * Where to send the user's browser: `{pod}/_system/auth/authorize` with `response_type=code`, the
   * given members and [pkce]'s challenge. The URL carries the pod's own host, not a session's
   * placeholder, because a browser opens it.
   *
   * [scope] is space-separated, as it goes on the wire. [state] is the caller's, and comes back with
   * the answer; make it unguessable and check it with [readRedirect].
   *
   * @throws IllegalArgumentException when [redirectUri] is not a URI, or its query carries a member of
   *   the authorization answer.
   */
  fun authorizationUrl(clientId: String, redirectUri: String, scope: String, state: String, pkce: SempodsPkce): HttpUrl =
    AuthorizationRequest.Builder(ResponseType.CODE, ClientID(clientId))
      .endpointURI(session.podBase.resolve(AUTHORIZE).toUri())
      .redirectionURI(redirectUri(redirectUri))
      .scope(Scope.parse(scope))
      .state(State(state))
      .codeChallenge(CodeVerifier(pkce.verifier), CodeChallengeMethod.S256)
      .build()
      .toURI()
      .toString()
      .toHttpUrl()

  /**
   * What the pod's `/authorize` sent the browser back with, read from the redirect's [encodedQuery]
   * as it arrived (RFC 6749 §4.1.2).
   *
   * Refused with a [SempodsClientException], before anything in it is used:
   *
   * - a `state` other than [expectedState], or none (RFC 6749 §10.12);
   * - an `iss` other than this pod's issuer, `{pod}/_system/auth` (RFC 9207). A pod that sends none is
   *   answered as it is;
   * - a response member (`code`, `state`, `iss`, `error`, `error_description`, `error_uri`) that
   *   appears twice, and a query that is neither a code nor an error. A redirect URI's own members may
   *   repeat.
   */
  @Throws(SempodsClientException::class)
  fun readRedirect(encodedQuery: String?, expectedState: String): SempodsAuthorizationRedirect {
    val parameters = URLUtils.parseParameters(encodedQuery)
    parameters.filter { (name, values) -> name in RESPONSE_MEMBERS && values.size > 1 }.keys.firstOrNull()?.let {
      throw SempodsClientException("The authorization redirect carries '$it' more than once.")
    }
    val response = try {
      AuthorizationResponse.parse(REDIRECT, parameters)
    } catch (_: ParseException) {
      throw SempodsClientException("The authorization redirect is neither a code nor an error.")
    }
    if (response.state != State(expectedState)) {
      throw SempodsClientException(
        "The authorization redirect does not carry the state this caller sent, so it is not the answer to its request.",
      )
    }
    if (response.issuer != null && response.issuer != Issuer(session.podBase.resolve(ISSUER).toString())) {
      throw SempodsClientException("The authorization redirect names another authorization server as its issuer.")
    }
    if (response is AuthorizationErrorResponse) {
      return SempodsAuthorizationRedirect.of(null, response.errorObject.code, response.errorObject.description)
    }
    // Nimbus reads a response without an error as a success, code or not.
    val code = response.toSuccessResponse().authorizationCode
      ?: throw SempodsClientException("The authorization redirect is neither a code nor an error.")
    return SempodsAuthorizationRedirect.of(code.value, null, null)
  }

  private fun registration(clientName: String, redirectUris: List<String>) =
    SempodsRepeatable.mark(session.newRequest("POST", REGISTER_ROUTE))
      .header("Accept", "application/json")
      .post(
        ClientMetadata().apply {
          name = clientName
          redirectionURIs = redirectUris.map(::redirectUri).toSet()
          grantTypes = setOf(GrantType.AUTHORIZATION_CODE)
          responseTypes = setOf(ResponseType.CODE)
          tokenEndpointAuthMethod = ClientAuthenticationMethod.NONE
        }.toJSONObject().toJSONString().toRequestBody(JSON_MEDIA_TYPE),
      )
      .build()

  private companion object {

    /**
     * [value] as a URI. A malformed one is the caller's argument, not a checked exception Java cannot
     * catch here. So is one whose query already carries a member of the answer, such as `iss`: the
     * answer would be ambiguous, and [readRedirect] would refuse it.
     */
    fun redirectUri(value: String): URI {
      val uri = try {
        URI(value)
      } catch (malformed: URISyntaxException) {
        throw IllegalArgumentException("'$value' is not a redirect URI: ${malformed.reason}", malformed)
      }
      val carried = URLUtils.parseParameters(uri.rawQuery).keys.firstOrNull { it in RESPONSE_MEMBERS }
      require(carried == null) { "'$value' carries '$carried', which the authorization answer adds itself." }
      return uri
    }

    const val AUTHORIZE = "_system/auth/authorize"

    const val ISSUER = "_system/auth"

    val RESPONSE_MEMBERS = setOf("code", "state", "iss", "error", "error_description", "error_uri")

    /** Nimbus reads a response against a redirect URI; the members are all it looks at here. */
    val REDIRECT: URI = URI("http://redirect.invalid/")

    val ANSWERS = (200..299).toSet()

    val PUBLIC_CLIENT = BodyReading<SempodsPublicClient> { bytes, _ ->
      val document = decodeObject(bytes)
      SempodsPublicClient.of(
        clientId = document.string("client_id"),
        clientName = document.stringOrNull("client_name"),
        redirectUris = document.strings("redirect_uris"),
      )
    }
  }
}
