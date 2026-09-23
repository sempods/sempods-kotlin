package org.sempods.api.pod.system.auth

import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.client.ClientInformation
import com.nimbusds.oauth2.sdk.client.ClientInformationResponse
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.client.ClientRegistrationErrorResponse
import com.nimbusds.oauth2.sdk.client.RegistrationError
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.SoftwareID
import com.nimbusds.oauth2.sdk.id.SoftwareVersion
import com.nimbusds.oauth2.sdk.token.BearerTokenError
import jakarta.ws.rs.core.Response
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationRefusal
import org.sempods.pods.oauth.flows.PodRegistrationResult
import java.net.URI
import java.util.Date

/**
 * A registration answer on the wire (RFC 7591 §§3.2.1–3.2.2, RFC 6750 §3).
 *
 * [PodClientRegistration][org.sempods.pods.oauth.flows.PodClientRegistration] decides what the
 * client is; the SDK decides how that is spelled. `ClientInformationResponse` is also what puts
 * `Cache-Control: no-store` on the answer and what writes `client_secret_expires_at: 0` for a
 * secret with no expiry — see `docs/auth/oauth.md` §"Installing a service client" for what a
 * caller makes of that.
 *
 * A refusal about the caller's own bearer carries no body: RFC 6750 §3 puts that in the
 * `WWW-Authenticate` challenge, and the SDK builds it.
 *
 * Members are not written in the order §3.2.1 lists them: the SDK's JSON object is a hash map. A
 * caller reads members by name, which is what JSON promises.
 */
internal object PodRegistrationResponses {

  fun render(realm: String, result: PodRegistrationResult): Response = when (result) {
    is PodRegistrationResult.Registered -> created(result)
    is PodRegistrationResult.ServiceRegistered -> created(result)
    is PodRegistrationResult.Refused -> refused(result.error, result.description)
    is PodRegistrationResult.Unauthorized -> unauthorized(realm, result.reason, result.description)
  }

  /**
   * A body this pod will not accept.
   *
   * Public because a body that is not a registration document at all never reaches the decision —
   * [PodRegistrationMessages] answers that one.
   */
  fun refused(error: PodRegistrationError, description: String): Response =
    jaxrs(ClientRegistrationErrorResponse(errorObject(error).setDescription(sanitize(description))).toHTTPResponse())

  private fun unauthorized(realm: String, reason: PodRegistrationRefusal, description: String): Response {
    val error = when (reason) {
      PodRegistrationRefusal.AUTHORITY_SPENT -> BearerTokenError.INVALID_TOKEN
      PodRegistrationRefusal.NOT_AUTHORIZED -> BearerTokenError.INSUFFICIENT_SCOPE
    }
    return jaxrs(ClientRegistrationErrorResponse(error.setDescription(sanitize(description)).setRealm(realm)).toHTTPResponse())
  }

  private fun created(client: PodRegistrationResult.Registered): Response {
    val metadata = ClientMetadata().apply {
      setRedirectionURIs(client.redirectUris.map(URI::create).toSet())
      setTokenEndpointAuthMethod(ClientAuthenticationMethod.NONE)
      setGrantTypes(setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN))
      setResponseTypes(setOf(ResponseType.CODE))
      client.clientName?.let { setName(it) }
      client.clientUri?.let { setURI(URI.create(it)) }
      client.logoUri?.let { setLogoURI(URI.create(it)) }
      client.softwareId?.let { setSoftwareID(SoftwareID(it)) }
      client.softwareVersion?.let { setSoftwareVersion(SoftwareVersion(it)) }
      // Left unset when empty: `"contacts": []` would claim the client named no way to reach it,
      // which is a different statement from saying nothing.
      client.contacts.takeIf { it.isNotEmpty() }?.let { setEmailContacts(it) }
      client.tosUri?.let { setTermsOfServiceURI(URI.create(it)) }
      client.policyUri?.let { setPolicyURI(URI.create(it)) }
    }
    return created(ClientInformation(ClientID(client.clientId), null, metadata, null))
  }

  private fun created(client: PodRegistrationResult.ServiceRegistered): Response {
    val metadata = ClientMetadata().apply {
      setTokenEndpointAuthMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      setGrantTypes(setOf(GrantType.CLIENT_CREDENTIALS))
      setName(client.clientName)
    }
    return created(
      ClientInformation(
        ClientID(client.clientId),
        Date.from(client.issuedAt),
        metadata,
        Secret(client.secret),
      ),
    )
  }

  private fun created(information: ClientInformation): Response =
    jaxrs(ClientInformationResponse(information, true).toHTTPResponse())

  private fun errorObject(error: PodRegistrationError): ErrorObject = when (error) {
    PodRegistrationError.INVALID_REDIRECT_URI -> RegistrationError.INVALID_REDIRECT_URI
    PodRegistrationError.INVALID_CLIENT_METADATA -> RegistrationError.INVALID_CLIENT_METADATA
  }

  /**
   * RFC 6749 §5.2's character set for `error_description` excludes `"` and `\`, and a refusal names
   * the value it refused — which came from the caller.
   */
  private fun sanitize(description: String): String = ErrorObject.removeIllegalChars(description)

  private fun jaxrs(response: HTTPResponse): Response {
    val builder = Response.status(response.statusCode).entity(response.body)
    response.headerMap.forEach { (name, values) -> values.forEach { builder.header(name, it) } }
    return builder.build()
  }
}
