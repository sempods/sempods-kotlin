package org.sempods.api.pod.system.auth

import com.nimbusds.oauth2.sdk.AuthorizationRequest
import com.nimbusds.oauth2.sdk.ParseException
import jakarta.ws.rs.core.MultivaluedMap
import org.sempods.pods.oauth.flows.PodAuthorizeRequest
import org.sempods.pods.oauth.flows.PodAuthorizeTerms

/**
 * Reading an authorization request (RFC 6749 §4.1.1, RFC 7636 §4.3, OIDC Core 1.0 §3.1.2.1).
 *
 * The grammar is the SDK's: `AuthorizationRequest` decides what `response_type`, `prompt` and
 * `scope` may look like, and a refusal carries its sentence. It is not an OpenID authentication
 * request, so no `openid` scope is asked for.
 *
 * Three things the parse reports are not used, because the flow owns them:
 *
 *  - **The address.** A parse failure may name the `redirect_uri` it read; the flow answers at an
 *    address only after it has validated it for the client, so the raw `client_id` and
 *    `redirect_uri` pass on as sent.
 *  - **`state`.** The SDK trims it and drops a blank one; RFC 6749 §4.1.2 has it returned exactly.
 *  - **`code_challenge` and its method.** The SDK trims the challenge too, where the flow refuses
 *    one sent with whitespace around it, and reads the method only beside a challenge.
 *
 * Only the parameters the route reads are parsed, each once. A repeated one is the flow's to refuse
 * (RFC 6749 §3.1), and one it does not read — `request_uri`, `resource` — is ignored however it is
 * written.
 */
internal object PodAuthorizeMessages {

  fun read(query: MultivaluedMap<String, String>): PodAuthorizeRequest = PodAuthorizeRequest(
    clientId = query.getFirst("client_id"),
    redirectUri = query.getFirst("redirect_uri"),
    state = query.getFirst("state"),
    terms = terms(query),
    repeated = AUTHORIZE_PARAMETERS.filterTo(sortedSetOf()) { (query[it]?.size ?: 0) > 1 },
  )

  private fun terms(query: MultivaluedMap<String, String>): PodAuthorizeTerms {
    val request = try {
      AuthorizationRequest.parse(project(query))
    } catch (e: ParseException) {
      return PodAuthorizeTerms.Malformed(e.errorObject?.description ?: e.message ?: "malformed authorization request")
    }
    return PodAuthorizeTerms.Read(
      responseType = request.responseType?.map { it.value }?.toSet().orEmpty(),
      codeChallenge = query.getFirst("code_challenge")?.takeIf { it.isNotBlank() },
      codeChallengeMethod = query.getFirst("code_challenge_method")?.trim()?.takeIf { it.isNotEmpty() },
      prompt = request.prompt?.map { it.toString() }?.toSet().orEmpty(),
      scopes = request.scope?.toStringList()?.toSet().orEmpty(),
    )
  }

  /** The parameters [read] parses, each once, trimmed, and a blank one absent. */
  private fun project(query: MultivaluedMap<String, String>): Map<String, List<String>> =
    AUTHORIZE_PARAMETERS.mapNotNull { name ->
      query.getFirst(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to listOf(it) }
    }.toMap()

  /** The parameters `/authorize` reads; another one may repeat, since it is ignored anyway. */
  private val AUTHORIZE_PARAMETERS = setOf(
    "response_type", "client_id", "redirect_uri", "state", "code_challenge", "code_challenge_method", "prompt", "scope",
  )
}
