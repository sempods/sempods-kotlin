package org.sempods.api.pod.system.auth

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.type.TypeReference
import com.nimbusds.common.contenttype.ContentType
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.client.ClientRegistrationRequest
import com.nimbusds.oauth2.sdk.client.RegistrationError
import com.nimbusds.oauth2.sdk.http.HTTPRequest
import org.sempods.commons.json.JsonMappers
import org.sempods.pods.oauth.flows.PodClientMetadata
import org.sempods.pods.oauth.flows.PodRegistrationError
import java.net.URI

/**
 * Reading a registration body (RFC 7591 §2, §3.1).
 *
 * The grammar is the SDK's: `ClientRegistrationRequest` decides what a registration document is,
 * which member may hold which type, and which of the two §3.2.2 codes a malformed one earns. What
 * this adds is the projection the decision works on — trimmed, with a blank read as absent, so the
 * dedup fingerprint sees the same values it always did.
 *
 * A member whose type the RFC does not allow — `"contacts": "a@b"` — is refused, so a client that
 * named a contact address the pod will not store learns it at registration.
 *
 * The `Authorization` header is deliberately not handed to the SDK. A bearer is this pod's
 * question, answered by `PodTokenAuthenticator` and the authorizer behind it; letting
 * `ClientRegistrationRequest` parse it would turn a malformed credential into a metadata error.
 */
internal object PodRegistrationMessages {

  /**
   * @param endpoint this pod's registration address. Carried by the SDK's request object and
   *   nothing else reads it; it is the real one so that no value here is a fiction.
   */
  fun read(endpoint: URI, body: String?): PodRegistrationRead {
    // No body at all is a registration that named nothing, and the decision answers that with the
    // sentence it has always answered: at least one redirect_uri is required.
    val text = body?.takeIf { it.isNotBlank() } ?: return PodRegistrationRead.Metadata(PodClientMetadata(), emptyMap())

    // Jackson first, and the order matters: it is the stricter reader, so a body it accepts is one
    // the SDK's JSON parser accepts too, and the two can never disagree about what arrived.
    val raw = try {
      JsonMappers.default().readValue(text, MAP)
    } catch (_: JacksonException) {
      return PodRegistrationRead.Unreadable(PodRegistrationError.INVALID_CLIENT_METADATA, "malformed JSON body")
    }

    val metadata = try {
      ClientRegistrationRequest.parse(httpRequest(endpoint, text)).clientMetadata
    } catch (e: ParseException) {
      return PodRegistrationRead.Unreadable(errorOf(e), describe(e))
    }

    return PodRegistrationRead.Metadata(project(metadata), raw)
  }

  private fun httpRequest(endpoint: URI, body: String): HTTPRequest =
    HTTPRequest(HTTPRequest.Method.POST, endpoint).apply {
      entityContentType = ContentType.APPLICATION_JSON
      setBody(body)
    }

  /**
   * Which of RFC 7591 §3.2.2's two codes the failure earns.
   *
   * The SDK marks a bad `redirect_uris` as such and leaves everything else unclassified; an
   * unclassified failure is a statement about a metadata field, which is the other code.
   */
  private fun errorOf(e: ParseException): PodRegistrationError =
    if (e.errorObject?.code == RegistrationError.INVALID_REDIRECT_URI.code) {
      PodRegistrationError.INVALID_REDIRECT_URI
    } else {
      PodRegistrationError.INVALID_CLIENT_METADATA
    }

  /** The SDK's own sentence, which names the member that failed. */
  private fun describe(e: ParseException): String =
    e.errorObject?.description ?: e.message ?: "the registration body is not valid client metadata"

  private fun project(metadata: ClientMetadata) = PodClientMetadata(
    redirectUris = metadata.redirectionURIStrings.orEmpty().mapNotNull(::trimmed).toSet(),
    clientName = trimmed(metadata.getName()),
    clientUri = trimmed(metadata.getURI()?.toString()),
    logoUri = trimmed(metadata.getLogoURI()?.toString()),
    softwareId = trimmed(metadata.getSoftwareID()?.value),
    softwareVersion = trimmed(metadata.getSoftwareVersion()?.value),
    contacts = metadata.emailContacts.orEmpty().mapNotNull(::trimmed),
    tosUri = trimmed(metadata.getTermsOfServiceURI()?.toString()),
    policyUri = trimmed(metadata.getPolicyURI()?.toString()),
  )

  private fun trimmed(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }

  private val MAP = object : TypeReference<Map<String, Any?>>() {}
}

/** A registration body, read or refused. */
internal sealed interface PodRegistrationRead {

  data class Metadata(val client: PodClientMetadata, val raw: Map<String, Any?>) : PodRegistrationRead

  /** Not a registration document at all — answered here, because syntax is this layer's. */
  data class Unreadable(val error: PodRegistrationError, val description: String) : PodRegistrationRead
}
