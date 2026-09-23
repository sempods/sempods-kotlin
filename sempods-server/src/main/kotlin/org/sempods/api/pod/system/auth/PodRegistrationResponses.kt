package org.sempods.api.pod.system.auth

import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ResponseType
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import com.nimbusds.oauth2.sdk.client.ClientInformation
import com.nimbusds.oauth2.sdk.client.ClientInformationResponse
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.client.ClientRegistrationErrorResponse
import com.nimbusds.oauth2.sdk.client.RegistrationError
import com.nimbusds.oauth2.sdk.http.HTTPResponse
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.oauth2.sdk.id.SoftwareID
import com.nimbusds.oauth2.sdk.id.SoftwareVersion
import jakarta.ws.rs.core.Response
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationResult
import java.net.URI

/**
 * A registration answer on the wire (RFC 7591 §§3.2.1–3.2.2).
 *
 * [PodClientRegistration][org.sempods.pods.oauth.flows.PodClientRegistration] decides what the
 * client is; the SDK decides how that is spelled. `ClientInformationResponse` is also what puts
 * `Cache-Control: no-store` on the answer — the rule a response that may carry a secret needs.
 *
 * Members are not written in the order RFC 7591 §3.2.1 lists them: the SDK's JSON object is a hash
 * map. A caller reads members by name, which is what JSON promises.
 */
internal object PodRegistrationResponses {

  fun render(result: PodRegistrationResult): Response = when (result) {
    is PodRegistrationResult.Registered -> created(result)
    is PodRegistrationResult.Refused -> refused(result.error, result.description)
  }

  /**
   * A body this pod will not accept.
   *
   * Public because a body that is not a registration document at all never reaches the decision —
   * [PodRegistrationMessages] answers that one.
   */
  fun refused(error: PodRegistrationError, description: String): Response {
    // RFC 6749 §5.2's character set for `error_description` excludes `"` and `\`, and a refusal
    // names the value it refused — which came from the caller.
    val sanitized = ErrorObject.removeIllegalChars(description)
    return jaxrs(ClientRegistrationErrorResponse(errorObject(error).setDescription(sanitized)).toHTTPResponse())
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
    val information = ClientInformation(ClientID(client.clientId), null, metadata, null)
    return jaxrs(ClientInformationResponse(information, true).toHTTPResponse())
  }

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
