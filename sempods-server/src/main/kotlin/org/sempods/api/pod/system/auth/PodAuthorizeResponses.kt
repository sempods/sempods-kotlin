package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.SempodsConfig
import org.sempods.SempodsUriBuilder
import org.sempods.auth.PodBrowserCookies
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.commons.net.UrlUtil
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.oauth.flows.PodAuthorizeRefusal
import org.sempods.pods.oauth.flows.PodAuthorizeResult
import org.sempods.pods.oauth.flows.PodConsentScreen
import java.net.URI
import java.time.Duration

/**
 * Every answer `GET /{pod}/_system/auth/authorize` gives, built in one place.
 *
 * [PodAuthorizeFlow][org.sempods.pods.oauth.flows.PodAuthorizeFlow] decides *what* to answer —
 * whether a code was minted, which boxes the dialog ticks, where the browser goes to sign in. This
 * decides how that reaches the wire: the status, the media type, the words, and the one cookie an
 * answer carries.
 *
 * The success redirect overwrites its parameters and never appends them, for the reason
 * [PodOAuthErrorResponses] gives for the error one.
 */
internal object PodAuthorizeResponses {

  fun render(
    result: PodAuthorizeResult,
    podName: String,
    cookies: PodBrowserCookies,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): Response = when (result) {
    is PodAuthorizeResult.Code -> {
      var callbackUri = UrlUtil.addOrUpdateQueryParameter(URI(result.target.uri), "code", result.code)
      result.state?.trim()?.takeIf { it.isNotBlank() }?.let {
        callbackUri = UrlUtil.addOrUpdateQueryParameter(callbackUri, "state", it)
      }
      Response.seeOther(callbackUri).build()
    }

    is PodAuthorizeResult.Login -> Response.temporaryRedirect(URI(result.authorizationUrl))
      .cookie(cookies.loginPin(podName, result.state, result.browserPin, LOGIN_PIN_TTL_SECONDS))
      .build()

    is PodAuthorizeResult.Consent ->
      Response.ok(consentPage(result.screen, templates, config), MediaType.TEXT_HTML).build()

    is PodAuthorizeResult.Error -> PodOAuthErrorResponses.render(result.delivery, config)

    is PodAuthorizeResult.Refused -> refusal(result.reason)
  }

  /** `/authorize`'s wording for a [PodAuthorizeRefusal], as plain text to whoever holds the browser. */
  private fun refusal(reason: PodAuthorizeRefusal): Response = when (reason) {
    PodAuthorizeRefusal.MISSING_REDIRECT_URI -> text(400, "missing redirect_uri")
    PodAuthorizeRefusal.UNREGISTERED_CLIENT -> text(400, UNREGISTERED_CLIENT_MESSAGE)
    PodAuthorizeRefusal.MALFORMED_CLIENT_ID -> text(400, "client_id must be a did:web or dyn: identity")
    PodAuthorizeRefusal.REDIRECT_URI_NOT_ALLOWED -> text(400, "redirect_uri not allowed for this client_id")
    PodAuthorizeRefusal.IDENTITY_PROVIDER_UNAVAILABLE -> text(503, "identity provider unavailable")
  }

  private fun text(status: Int, body: String): Response =
    Response.status(status).entity(body).type("text/plain").build()

  /**
   * The dialog, rendered.
   *
   * What is not on the screen is what is the same on every request: where the form posts, and the
   * rules it validates a typed context path against. Those are passed as data rather than hardcoded
   * in the template, so a change to [ContextPathRules] reaches the dialog on its own.
   */
  private fun consentPage(
    screen: PodConsentScreen,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): String = templates.render(
    "consent", mapOf(
      "consentAction" to "${config.apiBaseUrl}${screen.podName}/_system/auth/authorize/consent",
      "clientId" to screen.clientId,
      "clientName" to screen.clientName,
      "clientUri" to (screen.clientUri ?: ""),
      "logoUri" to (screen.logoUri ?: ""),
      "redirectUri" to screen.redirectUri,
      "state" to (screen.state ?: ""),
      "codeChallenge" to (screen.codeChallenge ?: ""),
      "codeChallengeMethod" to (screen.codeChallengeMethod ?: ""),
      "csrfToken" to screen.csrfToken,
      "webId" to screen.webId,
      "contexts" to screen.contexts,
      "podBaseUrl" to screen.podBaseUrl,
      // For the preview the form shows while a context is being typed. The posted value is the
      // relative path — the consent submission builds the IRI, there and nowhere else.
      "contextPathPrefix" to SempodsUriBuilder.CONTEXT_PATH_PREFIX,
      // The reserved names, so the form can say *why* a name is refused instead of the server
      // silently dropping it from the grant list.
      "reservedSegment" to ContextPathRules.RESERVED_SEGMENT,
      "delegationTypes" to ContextPathRules.DELEGATION_TYPES.joinToString(","),
      "implementedTypes" to ContextPathRules.IMPLEMENTED_TYPES.joinToString(","),
      "isOwner" to screen.isOwner,
      "publicContexts" to screen.publicContexts,
      "publicReadAvailable" to screen.publicContexts.isNotEmpty(),
      "publicReadPreselected" to screen.publicReadPreselected,
      "publicReadScope" to PUBLIC_READ_SCOPE,
      "durablePreselected" to screen.durablePreselected,
      "sessionIdle" to durationInWords(screen.sessionTerms.idle),
      "sessionAbsolute" to durationInWords(screen.sessionTerms.absolute),
      "durableIdle" to durationInWords(screen.durableTerms.idle),
      "durableAbsolute" to durationInWords(screen.durableTerms.absolute),
      "disconnectAvailable" to screen.disconnectAvailable,
    ))

  /**
   * A connection's term as the consent dialog says it: in days where it is whole days, in hours
   * otherwise — "4 days", "30 hours". The configuration states every term in whole hours or days.
   */
  internal fun durationInWords(duration: Duration): String {
    val hours = duration.toHours()
    return if (hours % 24 == 0L) counted(hours / 24, "day") else counted(hours, "hour")
  }

  private fun counted(count: Long, unit: String): String = if (count == 1L) "1 $unit" else "$count ${unit}s"

  /**
   * How long the browser keeps the login pin. Must outlive the parked request it guards
   * (`PodLoginStateStore`, 15 min) or the round trip times out at the shorter of the two.
   */
  private const val LOGIN_PIN_TTL_SECONDS = 15 * 60

  /**
   * What a well-formed `dyn:` client_id with no registration behind it is answered with.
   *
   * It names the RFC 6749 code in prose because the response cannot be the error *document* that
   * would normally carry it: at this point in `/authorize` the redirect address is not yet known
   * to belong to the client, so nothing may travel by redirect (`PodAuthorizeFlow` has the note).
   * Plain text going to whoever is holding the browser, then — and it says the one thing that
   * actually fixes it. Kept ASCII-only: the response declares no charset, so a typographic dash
   * would be the one part of it a client could garble.
   */
  internal const val UNREGISTERED_CLIENT_MESSAGE =
    "invalid_client: this pod holds no registration for that client_id. It was removed, it " +
        "expired, or it belongs to a different pod. Register again at the registration_endpoint " +
        "and restart authorization."
}
