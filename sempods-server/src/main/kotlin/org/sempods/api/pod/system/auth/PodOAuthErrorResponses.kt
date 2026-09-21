package org.sempods.api.pod.system.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.ws.rs.core.Response
import org.sempods.SempodsConfig
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Redirectable
import org.sempods.commons.logging.LogSafeText
import org.sempods.commons.net.UrlUtil
import java.net.URI

/**
 * How an authorization error becomes an HTTP answer — the rendering half of `auth-core`'s
 * [OAuthErrorDelivery], as `sempods-mcp` renders it for Ktor.
 *
 * The rule those types carry — and why a redirected error needs a [Redirectable] — is
 * [OAuthErrorDelivery]'s own KDoc. What this file adds is that neither branch can be written by
 * hand at a call site and get it wrong.
 *
 * **Parameters are overwritten, never appended**, and `error_uri` is removed where this deployment
 * has none to give. A registered `redirect_uri` may carry a query of its own — [org.sempods.auth.core.RedirectUri]
 * keeps it — so a client registered as `…/cb?error_uri=…` would otherwise receive its own value
 * back looking exactly like one this server chose. Overwrite or delete; never leave somebody
 * else's. A protocol library that appends would change this, which is what
 * `PodOAuthErrorResponsesTest` is there to catch.
 */
internal object PodOAuthErrorResponses {

  fun render(delivery: OAuthErrorDelivery, config: SempodsConfig): Response = when (delivery) {
    // No address to send it to is the same answer as an address that is blank, and the blank case
    // has to exist anyway for a parked request that lost its `redirect_uri`.
    is OAuthErrorDelivery.Direct ->
      renderToProvenAddress(null, delivery.code, delivery.description, null, config)

    is OAuthErrorDelivery.Redirect ->
      renderToProvenAddress(delivery.target.uri, delivery.code, delivery.description, delivery.state, config)
  }

  /**
   * The same answer, to an address proven somewhere this call cannot see.
   *
   * `oidc/callback` resumes a request parked up to fifteen minutes earlier and reports its failures
   * to the `redirect_uri` that request was validated with. The proof is in the parked record rather
   * than in a [Redirectable], and re-deriving it here would turn a redirect into a direct 400 for a
   * client whose registration was cleared in the meantime — a different answer to a different
   * question. That route moves onto [OAuthErrorDelivery] with #154, which owns it.
   */
  fun renderToProvenAddress(
    redirectUri: String?,
    error: OAuthErrorCode,
    description: String,
    state: String?,
    config: SempodsConfig,
  ): Response {
    audit(error, description, state, redirectUri)
    if (redirectUri.isNullOrBlank()) {
      return Response.status(400).entity("${error.code}: $description").type("text/plain").build()
    }
    var uri = UrlUtil.addOrUpdateQueryParameter(URI(redirectUri), "error", error.code)
    uri = UrlUtil.addOrUpdateQueryParameter(uri, "error_description", description)
    // The page a person can act on without reading a log — `docs/auth/oauth-errors.md` has a
    // heading per code this can emit, and the fragment anchors it. Only where a deployment has
    // said where that page is served (`SEMPODS_OAUTH_ERROR_DOC_BASE`): the parameter is optional
    // (RFC 6749 §4.1.2.1), and sending a person to a 404 is worse than sending them nowhere.
    val errorUri = config.oauthErrorDocBase?.let { OAuthErrors.errorUri(it, error) }
    uri = if (errorUri != null) {
      UrlUtil.addOrUpdateQueryParameter(uri, "error_uri", errorUri)
    } else {
      UrlUtil.removeQueryParameter(uri, "error_uri")
    }
    state?.trim()?.takeIf { it.isNotBlank() }?.let {
      uri = UrlUtil.addOrUpdateQueryParameter(uri, "state", it)
    }
    return Response.temporaryRedirect(uri).build()
  }

  /**
   * One structured line per authorize error, so a spike run can be reconstructed per client by
   * grepping `[oauth/authorize-audit]`.
   *
   * [description] is this server's own and stays plain; `state` and the address came from a client.
   */
  private fun audit(error: OAuthErrorCode, description: String, state: String?, redirectUri: String?) {
    logger.info {
      "[oauth/authorize-audit] outcome=error error=${error.code} error_description=\"$description\" " +
          "state=${LogSafeText.of(state ?: "(none)")} " +
          "redirect_uri=${LogSafeText.of(redirectUri ?: "(none)")}"
    }
  }

  private val logger = KotlinLogging.logger {}
}
