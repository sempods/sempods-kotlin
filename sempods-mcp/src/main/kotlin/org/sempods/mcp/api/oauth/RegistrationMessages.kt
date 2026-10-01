package org.sempods.mcp.api.oauth

import com.nimbusds.oauth2.sdk.ErrorObject
import com.nimbusds.oauth2.sdk.GrantType
import com.nimbusds.oauth2.sdk.ParseException
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
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import org.sempods.mcp.persist.oauth.DcrClient
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * A registration body read, and a registration answer written (RFC 7591 §§2, 3.1–3.2).
 *
 * The grammar is the SDK's, as it is at the pod's registration: `ClientMetadata` decides which
 * member may hold which type and which of the two §3.2.2 codes a malformed body earns, and
 * `ClientInformationResponse` spells the answer, `Cache-Control: no-store` included. What this adds
 * is the projection the service stores: the redirect URIs, and the name, `software_id` and
 * `software_version` trimmed. A blank name is read as absent; a blank `software_id` or
 * `software_version` is refused as `invalid_client_metadata`, as the SDK refuses it.
 *
 * Jackson reads the body first because it is the stricter parser. The SDK's accepts unquoted keys.
 */
internal object RegistrationMessages {

  fun read(body: String, objectMapper: ObjectMapper): RegistrationRead {
    // `readValue` answers the JSON literal `null` with `null` and no exception.
    val isObject = try {
      objectMapper.readValue(body, Map::class.java) != null
    } catch (_: JacksonException) {
      false
    }
    if (!isObject) return refused(RegistrationError.INVALID_CLIENT_METADATA, "malformed JSON body")

    val metadata = try {
      ClientMetadata.parse(JSONObjectUtils.parse(body))
    } catch (e: ParseException) {
      return refused(errorOf(e), e.errorObject?.description ?: e.message ?: NOT_CLIENT_METADATA)
    } catch (_: RuntimeException) {
      // The SDK lets two failures past its `ParseException`: a blank `software_version` throws
      // from the identifier's constructor, and a `null` among the `redirect_uris` from `URI`'s.
      // Both mean the body is not client metadata, which is a 400.
      return refused(RegistrationError.INVALID_CLIENT_METADATA, NOT_CLIENT_METADATA)
    }

    return RegistrationRead.Metadata(
      redirectUris = metadata.redirectionURIs.orEmpty().mapTo(LinkedHashSet(), URI::toString),
      clientName = trimmed(metadata.getName()),
      softwareId = trimmed(metadata.getSoftwareID()?.value),
      softwareVersion = trimmed(metadata.getSoftwareVersion()?.value),
    )
  }

  /**
   * The §3.2.1 answer for [client], naming the current request's [redirectUris] — see the note at
   * the call site.
   *
   * A member the registration does not hold is left out: a nameless client's answer has no
   * `client_name`.
   */
  fun created(client: DcrClient, redirectUris: Set<String>): HTTPResponse {
    val metadata = ClientMetadata().apply {
      setRedirectionURIs(redirectUris.mapTo(LinkedHashSet(), URI::create))
      setTokenEndpointAuthMethod(ClientAuthenticationMethod.NONE)
      setGrantTypes(setOf(GrantType.AUTHORIZATION_CODE, GrantType.REFRESH_TOKEN))
      setResponseTypes(setOf(ResponseType.CODE))
      // A row stored before this reader trimmed may still hold a blank value, and the SDK's
      // identifiers refuse one from their constructors.
      trimmed(client.clientName)?.let { setName(it) }
      trimmed(client.softwareId)?.let { setSoftwareID(SoftwareID(it)) }
      trimmed(client.softwareVersion)?.let { setSoftwareVersion(SoftwareVersion(it)) }
    }
    return ClientInformationResponse(ClientInformation(ClientID(client.clientId), null, metadata, null), true)
      .toHTTPResponse()
  }

  /**
   * A §3.2.2 refusal. The description may name the value refused, which came from the caller, so it
   * passes RFC 6749 §5.2's character set first.
   */
  fun refusal(error: ErrorObject, description: String): HTTPResponse =
    ClientRegistrationErrorResponse(error.setDescription(ErrorObject.removeIllegalChars(description))).toHTTPResponse()

  /**
   * Which of RFC 7591 §3.2.2's two codes a parse failure earns: the SDK marks a bad redirect URI as
   * such, and anything else it refuses is a statement about a metadata field.
   */
  private fun errorOf(e: ParseException): ErrorObject =
    if (e.errorObject?.code == RegistrationError.INVALID_REDIRECT_URI.code) RegistrationError.INVALID_REDIRECT_URI
    else RegistrationError.INVALID_CLIENT_METADATA

  private fun refused(error: ErrorObject, description: String) = RegistrationRead.Refused(refusal(error, description))

  private fun trimmed(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

  private const val NOT_CLIENT_METADATA = "the registration body is not valid client metadata"
}

/** A registration body, read or refused. */
internal sealed interface RegistrationRead {

  data class Metadata(
    val redirectUris: Set<String>,
    val clientName: String?,
    val softwareId: String?,
    val softwareVersion: String?,
  ) : RegistrationRead

  data class Refused(val response: HTTPResponse) : RegistrationRead
}

/**
 * Sends an SDK-built answer as it stands. Ktor sets `Content-Type` from the body's own argument and
 * refuses it as a header, so it travels there; every other header is copied.
 */
internal suspend fun ApplicationCall.respondRegistration(response: HTTPResponse) {
  response.headerMap.forEach { (name, values) ->
    if (!name.equals(HttpHeaders.ContentType, ignoreCase = true)) values.forEach { this.response.headers.append(name, it) }
  }
  respondText(
    response.body.orEmpty(),
    response.getHeaderValue(HttpHeaders.ContentType)?.let(ContentType::parse) ?: ContentType.Application.Json,
    HttpStatusCode.fromValue(response.statusCode),
  )
}
