package org.sempods.api.pod.system.auth

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.sempods.SempodsConfig
import org.sempods.SempodsUriBuilder
import org.sempods.auth.PodBrowserCookies
import org.sempods.commons.net.UrlUtil
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.SERVICE_CLIENTS_INSTALL_SCOPE
import org.sempods.pods.oauth.flows.PodAuthorizeRefusal
import org.sempods.pods.oauth.flows.PodAuthorizeResult
import org.sempods.pods.oauth.flows.PodConsentRefusal
import org.sempods.pods.oauth.flows.PodConsentResult
import org.sempods.pods.oauth.flows.PodConsentScreen
import org.sempods.pods.oauth.flows.PodServiceClientGrantRefusal
import org.sempods.pods.oauth.flows.PodServiceClientGrantResult
import org.sempods.pods.oauth.flows.PodServiceClientGrantScreen
import java.net.URI
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Every answer the pod's two browser routes give — `GET authorize` and the consent submission it
 * sends the person to — built in one place.
 *
 * [PodAuthorizeFlow][org.sempods.pods.oauth.flows.PodAuthorizeFlow] decides *what* to answer —
 * whether a code was minted, which boxes the dialog ticks, where the browser goes to sign in. This
 * decides how that reaches the wire: the status, the media type, the words, and the one cookie an
 * answer carries.
 *
 */
internal object PodAuthorizeResponses {

  fun render(
    result: PodAuthorizeResult,
    podName: String,
    cookies: PodBrowserCookies,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): Response = when (result) {
    is PodAuthorizeResult.Code -> codeRedirect(result.code, result.target.uri, result.state)

    is PodAuthorizeResult.Login -> Response.temporaryRedirect(URI(result.authorizationUrl))
      .cookie(cookies.loginPin(podName, result.state, result.browserPin, LOGIN_PIN_TTL_SECONDS))
      .build()

    is PodAuthorizeResult.Consent ->
      Response.ok(consentPage(result.screen, templates, config), MediaType.TEXT_HTML).build()

    is PodAuthorizeResult.Error -> PodOAuthErrorResponses.render(result.delivery, config)

    is PodAuthorizeResult.Refused -> refusal(result.reason)
  }

  fun render(
    result: PodConsentResult,
    podName: String,
    cookies: PodBrowserCookies,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): Response = when (result) {
    is PodConsentResult.Code -> codeRedirect(result.code, result.target.uri, result.state)

    is PodConsentResult.Error -> PodOAuthErrorResponses.render(result.delivery, config)

    // The client is told the request was denied, and the browser is told the sign-in is over. Both
    // halves are this one answer: a person who signed out and kept their cookie signed out of
    // nothing.
    is PodConsentResult.SignedOut ->
      Response.fromResponse(PodOAuthErrorResponses.render(result.delivery, config))
        .cookie(cookies.clearSession(podName))
        .build()

    is PodConsentResult.Refused -> refusal(result.reason)
  }

  /** The grant consent's answers. */
  fun render(
    result: PodServiceClientGrantResult,
    podName: String,
    cookies: PodBrowserCookies,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): Response = when (result) {
    is PodServiceClientGrantResult.Screen ->
      Response.ok(grantPage(result.screen, templates, config), MediaType.TEXT_HTML).build()

    is PodServiceClientGrantResult.Login -> Response.temporaryRedirect(URI(result.authorizationUrl))
      .cookie(cookies.loginPin(podName, result.state, result.browserPin, LOGIN_PIN_TTL_SECONDS))
      .build()

    is PodServiceClientGrantResult.Granted -> {
      // Overwriting, never appending, for the reason [codeRedirect] gives.
      var uri = UrlUtil.addOrUpdateQueryParameter(URI(result.target.uri), "result", GRANTED)
      uri = UrlUtil.addOrUpdateQueryParameter(uri, "scope", result.scopes.sorted().joinToString(" "))
      result.state?.let { uri = UrlUtil.addOrUpdateQueryParameter(uri, "state", it) }
      Response.seeOther(uri).build()
    }

    is PodServiceClientGrantResult.Error -> PodOAuthErrorResponses.render(result.delivery, config)

    is PodServiceClientGrantResult.Refused -> when (result.reason) {
      PodServiceClientGrantRefusal.MISSING_REDIRECT_URI -> refusal(PodAuthorizeRefusal.MISSING_REDIRECT_URI)
      PodServiceClientGrantRefusal.UNREGISTERED_CLIENT -> refusal(PodAuthorizeRefusal.UNREGISTERED_CLIENT)
      PodServiceClientGrantRefusal.MALFORMED_CLIENT_ID -> refusal(PodAuthorizeRefusal.MALFORMED_CLIENT_ID)
      PodServiceClientGrantRefusal.REDIRECT_URI_NOT_ALLOWED -> refusal(PodAuthorizeRefusal.REDIRECT_URI_NOT_ALLOWED)
      PodServiceClientGrantRefusal.IDENTITY_PROVIDER_UNAVAILABLE -> refusal(PodAuthorizeRefusal.IDENTITY_PROVIDER_UNAVAILABLE)
      PodServiceClientGrantRefusal.SESSION_EXPIRED -> text(401, "session expired — please open the grant again")
      PodServiceClientGrantRefusal.FORM_EXPIRED -> text(403, "this form is no longer valid — please open the grant again")
    }
  }

  /** The grant dialog. Each scope shows as its context path relative to the pod, and the permission. */
  private fun grantPage(
    screen: PodServiceClientGrantScreen,
    templates: TemplateRenderer,
    config: SempodsConfig,
  ): String = templates.render(
    "service-client-grant", mapOf(
      "grantAction" to "${config.apiBaseUrl}${screen.podName}/_system/auth/grant",
      "requesterName" to screen.requesterName,
      "serviceClientId" to screen.serviceClientId,
      "serviceLabel" to screen.serviceLabel,
      "registeredAt" to DateTimeFormatter.ISO_INSTANT.format(screen.registeredAt.truncatedTo(ChronoUnit.SECONDS)),
      "requested" to screen.requested.map { GrantRow.of(it, screen.podBaseUrl) },
      "held" to screen.held.map { GrantRow.of(it, screen.podBaseUrl) },
      "csrfToken" to screen.csrfToken,
      "webId" to screen.webId,
    ))

  /** One scope as the grant dialog shows it. Read by the template by name. */
  internal data class GrantRow(val scope: String, val context: String, val permission: String) {
    companion object {
      fun of(scope: String, podBaseUrl: String): GrantRow {
        val context = scope.substringBeforeLast('#')
        val base = podBaseUrl.trimEnd('/') + "/"
        return GrantRow(scope, context.removePrefix(base).ifEmpty { context }, scope.substringAfterLast('#'))
      }
    }
  }

  private const val GRANTED = "granted"

  /**
   * Where the code goes, with `state` beside it exactly as
   * [suppliedState][org.sempods.pods.oauth.flows.suppliedState] left it.
   *
   * Overwriting and never appending, for the reason [PodOAuthErrorResponses] gives: a registered
   * address may carry a query of its own, and a client registered as `…/cb?code=…` must not receive
   * its own value back looking like a code this server issued.
   */
  private fun codeRedirect(code: String, redirectUri: String, state: String?): Response {
    var callbackUri = UrlUtil.addOrUpdateQueryParameter(URI(redirectUri), "code", code)
    state?.let { callbackUri = UrlUtil.addOrUpdateQueryParameter(callbackUri, "state", it) }
    return Response.seeOther(callbackUri).build()
  }

  /** `/authorize`'s wording for a [PodAuthorizeRefusal], as plain text to whoever holds the browser. */
  private fun refusal(reason: PodAuthorizeRefusal): Response = when (reason) {
    PodAuthorizeRefusal.MISSING_REDIRECT_URI -> text(400, "missing redirect_uri")
    PodAuthorizeRefusal.UNREGISTERED_CLIENT -> text(400, UNREGISTERED_CLIENT_MESSAGE)
    PodAuthorizeRefusal.MALFORMED_CLIENT_ID -> text(400, "client_id must be a did:web or dyn: identity")
    PodAuthorizeRefusal.REDIRECT_URI_NOT_ALLOWED -> text(400, "redirect_uri not allowed for this client_id")
    PodAuthorizeRefusal.IDENTITY_PROVIDER_UNAVAILABLE -> text(503, "identity provider unavailable")
  }

  /**
   * The consent submission's wording.
   *
   * It shares three sentences with the table above. The fourth differs: a cleared registration is
   * "invalid client_id" here and a complaint about the format at `/authorize`.
   */
  private fun refusal(reason: PodConsentRefusal): Response = when (reason) {
    PodConsentRefusal.MISSING_REDIRECT_URI -> text(400, "missing redirect_uri")
    PodConsentRefusal.UNREGISTERED_CLIENT -> text(400, UNREGISTERED_CLIENT_MESSAGE)
    PodConsentRefusal.MALFORMED_CLIENT_ID -> text(400, "invalid client_id")
    PodConsentRefusal.REDIRECT_URI_NOT_ALLOWED -> text(400, "redirect_uri not allowed for this client_id")
    PodConsentRefusal.SESSION_EXPIRED -> text(401, "session expired — please re-authorize")
    PodConsentRefusal.FORM_EXPIRED -> text(403, "this form is no longer valid — please re-authorize")
  }

  /**
   * A refusal in words, with the charset stated.
   *
   * These sentences carry an em-dash and Jersey writes UTF-8. Leave the charset out and a client
   * that falls back to ISO-8859-1 shows `session expired â€” please re-authorize`.
   */
  private fun text(status: Int, body: String): Response =
    Response.status(status).entity(body).type("text/plain;charset=UTF-8").build()

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
      // The owner may build a context, on a dialog that is about contexts. The installation screen
      // is not: `PodConsentFlow.privilegedAuthority` refuses a `new_context` it is posted, so the form and
      // the script behind it would only offer work that cannot land.
      "contextCreationAvailable" to (screen.isOwner && screen.privilegedFeatures.isEmpty()),
      "publicContexts" to screen.publicContexts,
      "publicReadAvailable" to screen.publicContexts.isNotEmpty(),
      "publicReadPreselected" to screen.publicReadPreselected,
      "publicReadScope" to PUBLIC_READ_SCOPE,
      "durablePreselected" to screen.durablePreselected,
      "lifetimeAvailable" to screen.lifetimeAvailable,
      "sessionIdle" to durationInWords(screen.sessionTerms.idle),
      "sessionAbsolute" to durationInWords(screen.sessionTerms.absolute),
      "durableIdle" to durationInWords(screen.durableTerms.idle),
      "durableAbsolute" to durationInWords(screen.durableTerms.absolute),
      "disconnectAvailable" to screen.disconnectAvailable,
      // A flag per feature: the template's sentence says what this one allows, and a generic one
      // over a list would say nothing a person could weigh.
      "installerRequested" to (SERVICE_CLIENTS_INSTALL_SCOPE in screen.privilegedFeatures),
      "installerScope" to SERVICE_CLIENTS_INSTALL_SCOPE,
      "managementRequested" to (SERVICE_CLIENTS_MANAGE_SCOPE in screen.privilegedFeatures),
      "managementScope" to SERVICE_CLIENTS_MANAGE_SCOPE,
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
   * actually fixes it.
   */
  internal const val UNREGISTERED_CLIENT_MESSAGE =
    "invalid_client: this pod holds no registration for that client_id. It was removed, it " +
        "expired, or it belongs to a different pod. Register again at the registration_endpoint " +
        "and restart authorization."
}
