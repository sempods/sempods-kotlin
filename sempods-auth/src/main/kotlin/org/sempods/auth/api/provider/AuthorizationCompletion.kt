package org.sempods.auth.api.provider

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.DidWeb
import org.sempods.auth.login.ConsentTransactionStore
import org.sempods.auth.login.ConsentTransactionStore.PendingConsent
import org.sempods.auth.login.StateStore.PendingAuthorize
import org.sempods.auth.persist.ConsentDecisionDao
import org.sempods.auth.persist.WebIdProfileDao
import java.net.URI

/**
 * The last step of an authorization: the person has signed in, and the parked request is answered
 * with a code, or first with the agreement screen. The rules are `identity-service.md`
 * §"Agreement".
 *
 * The scopes a code is issued with are the ones the token endpoint releases claims for, so they are
 * narrowed here, never later: what the person agreed to, plus `openid`. Every code issued here
 * carries [AGREEMENT_TEXT] as its `consentText`, and the token endpoint releases claims about the
 * person only for a code that does. A code from anywhere else — a node still running the release
 * before this step existed, during a rolling deploy — names the WebID and nothing more.
 */
class AuthorizationCompletion(
  private val issuer: String,
  private val authorizationCodeStore: AuthorizationCodeStore,
  private val consentDecisions: ConsentDecisionDao,
  private val consentTransactions: ConsentTransactionStore,
  private val webIdProfileDao: WebIdProfileDao,
) {

  /** Answers [pending] for [webId], who has just signed in. */
  suspend fun afterSignIn(call: ApplicationCall, webId: String, pending: PendingAuthorize) {
    val asked = pending.scopes - OPENID
    val agreed = consentDecisions.agreedScopes(webId, pending.clientId)
    if (asked.isEmpty() || (!pending.promptConsent && agreed.containsAll(asked))) {
      return call.respondRedirect(codeRedirect(webId, pending, released = pending.scopes))
    }
    val key = consentTransactions.park(PendingConsent(webId, pending))
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    // A consent screen that can be framed can be clicked through by the page that frames it.
    call.response.headers.append("X-Frame-Options", "DENY")
    call.response.headers.append("Content-Security-Policy", "frame-ancestors 'none'")
    call.respondText(
      ConsentPage.render(
        client = displayNameOf(pending.clientId, pending.redirectUri),
        asked = asked,
        signedInAs = webIdProfileDao.findByUri(webId)?.displayName?.takeIf { it.isNotBlank() } ?: webId,
        consentKey = key,
      ),
      ContentType.Text.Html,
    )
  }

  /**
   * Answers the request parked under [consentKey] with the person's decision. `false` when there is
   * no such request — unknown, already answered or expired — and nothing was answered.
   */
  suspend fun afterDecision(call: ApplicationCall, consentKey: String, allowed: Boolean): Boolean {
    val consent = consentTransactions.consume(consentKey) ?: return false
    val pending = consent.authorize
    val released = if (allowed) {
      consentDecisions.agree(consent.webId, pending.clientId, pending.scopes - OPENID)
      pending.scopes
    } else {
      setOf(OPENID)
    }
    call.respondRedirect(codeRedirect(consent.webId, pending, released))
    return true
  }

  /** The code answer, carrying [released] as the scopes the token endpoint will honour. */
  private fun codeRedirect(webId: String, pending: PendingAuthorize, released: Set<String>): String {
    val code = authorizationCodeStore.issue(
      subject = webId,
      realm = issuer,
      clientId = pending.clientId,
      scopes = released,
      redirectUri = pending.redirectUri,
      codeChallenge = pending.codeChallenge,
      codeChallengeMethod = pending.codeChallengeMethod,
      nonce = pending.nonce,
      consentText = AGREEMENT_TEXT,
    )
    return AuthorizationResponses.code(pending.redirectUri, code, pending.clientState, issuer)
  }

  /**
   * The client as the redirect policy verified it: the host of its redirect address, and for a
   * path-scoped `did:web` identifier its path too. `did:web:apps.example:console` and
   * `did:web:apps.example:reports` are different clients with different agreements, so the screen
   * must not show both as `apps.example`.
   */
  private fun displayNameOf(clientId: String, redirectUri: String): String {
    val uri = URI(redirectUri)
    val authority = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
    val path = DidWeb.targetOf(clientId)?.pathPrefix?.takeIf { it != "/" }.orEmpty()
    return authority + path
  }

  companion object {

    /**
     * The version of the agreement step a code passed through, recorded as the code's
     * `consentText`. Raise it when the screen starts asking about something new.
     */
    const val AGREEMENT_TEXT = 1

    private const val OPENID = "openid"
  }
}
