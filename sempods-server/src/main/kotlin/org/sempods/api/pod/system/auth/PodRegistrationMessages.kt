package org.sempods.api.pod.system.auth

import com.fasterxml.jackson.core.JacksonException
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.client.ClientMetadata
import com.nimbusds.oauth2.sdk.client.RegistrationError
import com.nimbusds.oauth2.sdk.util.JSONObjectUtils
import org.sempods.commons.json.JsonMappers
import org.sempods.commons.json.JsonUtil
import org.sempods.pods.oauth.flows.PodClientMetadata
import org.sempods.pods.oauth.flows.PodRegistrationError
import org.sempods.pods.oauth.flows.PodRegistrationResult

/**
 * Reading a registration body (RFC 7591 §2, §3.1).
 *
 * The grammar is the SDK's: `ClientMetadata` decides what a registration document is, which member
 * may hold which type, and which of the two §3.2.2 codes a malformed one earns. What this adds is
 * the projection the decision works on — trimmed, with a blank read as absent, so the dedup
 * fingerprint sees the same values it always did.
 *
 * A member whose type the RFC does not allow — `"contacts": "a@b"` — is refused, so a client that
 * named a contact address the pod will not store learns it at registration.
 *
 * **Two readers, and each has a job.** Jackson produces the verbatim map the registration row
 * stores, in the types that row has always held; the SDK produces the typed view. Jackson runs
 * first because it is the stricter of the two, so a body it accepts is one the SDK's parser accepts
 * as well.
 */
internal object PodRegistrationMessages {

  fun read(body: String?): PodRegistrationRead {
    // No body at all is a registration that named nothing, and the decision answers that with the
    // sentence it has always answered: at least one redirect_uri is required.
    val text = body?.takeIf { it.isNotBlank() } ?: EMPTY_BODY

    val raw = try {
      JsonMappers.default().readValue(text, JsonUtil.dynamicTypeRef)
    } catch (_: JacksonException) {
      return unreadable(PodRegistrationError.INVALID_CLIENT_METADATA, "malformed JSON body")
    }

    val metadata = try {
      ClientMetadata.parse(JSONObjectUtils.parse(text))
    } catch (e: ParseException) {
      return unreadable(errorOf(e), describe(e))
    }

    return PodRegistrationRead.Metadata(project(metadata), raw)
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

  private fun unreadable(error: PodRegistrationError, description: String) =
    PodRegistrationRead.Unreadable(PodRegistrationResult.Refused(error, description))

  /** What an absent body is read as, so that both readers answer it the way they answer `{}`. */
  private const val EMPTY_BODY = "{}"
}

/** A registration body, read or refused. */
internal sealed interface PodRegistrationRead {

  data class Metadata(val client: PodClientMetadata, val raw: Map<String, Any?>) : PodRegistrationRead

  /** Not a registration document at all — answered here, because syntax is this layer's. */
  data class Unreadable(val refusal: PodRegistrationResult.Refused) : PodRegistrationRead
}
