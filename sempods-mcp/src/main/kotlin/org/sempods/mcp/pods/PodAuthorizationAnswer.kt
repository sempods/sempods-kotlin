package org.sempods.mcp.pods

import com.nimbusds.oauth2.sdk.AuthorizationErrorResponse
import com.nimbusds.oauth2.sdk.AuthorizationResponse
import com.nimbusds.oauth2.sdk.ParseException
import com.nimbusds.oauth2.sdk.util.URLUtils
import java.net.URI

/** What a pod's redirect to the connect callback says, as [readPodAuthorizationAnswer] reads it. */
internal sealed interface PodAuthorizationAnswer {

  /** The pod issued [code]. */
  data class Code(val code: String) : PodAuthorizationAnswer

  /** The pod refused, with an RFC 6749 [error] code. */
  data class Refused(val error: String) : PodAuthorizationAnswer

  /** Neither a code nor an error: nothing to act on. */
  data object Malformed : PodAuthorizationAnswer

  /**
   * Not the pod's answer to this connect: a response member came twice, or `iss` names another
   * authorization server, or is missing where the pod promised it. [reason] is for the log.
   */
  data class NotFromPod(val reason: String) : PodAuthorizationAnswer
}

/**
 * Reads the pod's redirect from its raw [encodedQuery], for the connect whose discovered
 * [metadata] it must answer.
 *
 * `iss` is checked before anything else in the answer is used, an error included (RFC 9207 §2.4).
 * This service is a client of pods it does not host, which is the mix-up RFC 9207 was written for:
 * an `iss` other than [PodOAuthMetadata.issuer] means another authorization server answered.
 * A pod that sends no `iss` is answered as it is, unless its metadata set
 * `authorization_response_iss_parameter_supported`. A terminating `/` is not compared, as in
 * [PodOAuthClient.discoverMetadata].
 *
 * A response member sent twice is no single answer, so it is refused rather than read first-wins:
 * a second `iss` must not hide behind a first.
 */
internal fun readPodAuthorizationAnswer(encodedQuery: String?, metadata: PodOAuthMetadata): PodAuthorizationAnswer {
  val parameters = URLUtils.parseParameters(encodedQuery)
  parameters.entries.firstOrNull { (name, values) -> name in RESPONSE_MEMBERS && values.size > 1 }?.let {
    return PodAuthorizationAnswer.NotFromPod("'${it.key}' sent more than once")
  }
  val response = try {
    AuthorizationResponse.parse(REDIRECT, parameters)
  } catch (_: ParseException) {
    return PodAuthorizationAnswer.Malformed
  }
  val issuer = response.issuer?.value
  if (issuer != null && issuer.trimEnd('/') != metadata.issuer) {
    return PodAuthorizationAnswer.NotFromPod("iss names another authorization server")
  }
  if (issuer == null && metadata.authorizationResponseIssParameterSupported) {
    return PodAuthorizationAnswer.NotFromPod("iss missing, though the pod advertises it")
  }
  if (response is AuthorizationErrorResponse) return PodAuthorizationAnswer.Refused(response.errorObject.code)
  // The SDK reads a response without an error as a success, code or not.
  val code = response.toSuccessResponse().authorizationCode?.value
  return if (code.isNullOrBlank()) PodAuthorizationAnswer.Malformed else PodAuthorizationAnswer.Code(code)
}

/** The members RFC 6749 §4.1.2 and RFC 9207 put into an authorization response. */
private val RESPONSE_MEMBERS = setOf("code", "state", "iss", "error", "error_description", "error_uri")

/** The SDK wants the address the response arrived at; nothing here reads it. */
private val REDIRECT = URI("http://redirect.invalid/")
