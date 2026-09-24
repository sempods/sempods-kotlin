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
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.pods.oauth.flows.PodRegistrationError
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
 * Members are not written in the order §3.2.1 lists them: the SDK's JSON object is a hash map. A
 * caller reads members by name, which is what JSON promises.
 */
internal object PodRegistrationResponses {

  /**
   * @param challenge builds this pod's `WWW-Authenticate` for an RFC 6750 error code. Passed in
   *   rather than built here so that every 401 and 403 on the pod carries the one challenge shape
   *   `SempodsBaseEndpoint` owns, RFC 9728 pointer included.
   */
  fun render(result: PodRegistrationResult, challenge: (error: String) -> String): Response = when (result) {
    is PodRegistrationResult.Registered -> created(publicClient(result))
    is PodRegistrationResult.ServiceRegistered -> created(serviceClient(result))
    is PodRegistrationResult.Refused -> refused(result)

    is PodRegistrationResult.Unauthorized -> Response.status(result.reason.status)
      .header(HttpHeaders.WWW_AUTHENTICATE, challenge(result.reason.error))
      .entity(result.description)
      .type(MediaType.TEXT_PLAIN)
      .build()
  }

  private fun refused(result: PodRegistrationResult.Refused): Response {
    // RFC 6749 §5.2's character set for `error_description` excludes `"` and `\`, and a refusal
    // names the value it refused — which came from the caller.
    val sanitized = ErrorObject.removeIllegalChars(result.description)
    return jaxrs(ClientRegistrationErrorResponse(errorObject(result.error).setDescription(sanitized)).toHTTPResponse())
  }

  private fun publicClient(client: PodRegistrationResult.Registered): ClientInformation {
    val metadata = ClientMetadata().apply {
      setRedirectionURIs(client.client.redirectUris.map(URI::create).toSet())
      setTokenEndpointAuthMethod(ClientAuthenticationMethod.NONE)
      setGrantTypes(setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN))
      setResponseTypes(setOf(ResponseType.CODE))
      client.client.clientName?.let { setName(it) }
      client.client.clientUri?.let { setURI(URI.create(it)) }
      client.client.logoUri?.let { setLogoURI(URI.create(it)) }
      client.client.softwareId?.let { setSoftwareID(SoftwareID(it)) }
      client.client.softwareVersion?.let { setSoftwareVersion(SoftwareVersion(it)) }
      // Left unset when empty: `"contacts": []` would claim the client named no way to reach it,
      // which is a different statement from saying nothing.
      client.client.contacts.takeIf { it.isNotEmpty() }?.let { setEmailContacts(it) }
      client.client.tosUri?.let { setTermsOfServiceURI(URI.create(it)) }
      client.client.policyUri?.let { setPolicyURI(URI.create(it)) }
    }
    return ClientInformation(ClientID(client.clientId), null, metadata, null)
  }

  private fun serviceClient(client: PodRegistrationResult.ServiceRegistered): ClientInformation {
    val metadata = ClientMetadata().apply {
      setTokenEndpointAuthMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
      setGrantTypes(setOf(GrantType.CLIENT_CREDENTIALS))
      setName(client.clientName)
    }
    return ClientInformation(ClientID(client.clientId), Date.from(client.issuedAt), metadata, Secret(client.secret))
  }

  private fun created(information: ClientInformation): Response =
    jaxrs(ClientInformationResponse(information, true).toHTTPResponse())

  private fun errorObject(error: PodRegistrationError): ErrorObject = when (error) {
    PodRegistrationError.INVALID_REDIRECT_URI -> RegistrationError.INVALID_REDIRECT_URI
    PodRegistrationError.INVALID_CLIENT_METADATA -> RegistrationError.INVALID_CLIENT_METADATA
  }

  private fun jaxrs(response: HTTPResponse): Response {
    val builder = Response.status(response.statusCode).entity(response.body)
    response.headerMap.forEach { (name, values) -> values.forEach { builder.header(name, it) } }
    return builder.build()
  }
}
