package org.sempods.auth.api.provider

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import org.sempods.auth.core.AuthorizationCodeStore
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
 * narrowed here, never later: what the person agreed to, plus `openid`.
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
        clientHost = hostOf(pending.redirectUri),
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
    )
    return AuthorizationResponses.code(pending.redirectUri, code, pending.clientState, issuer)
  }

  private fun hostOf(redirectUri: String): String {
    val uri = URI(redirectUri)
    return if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
  }

  private companion object {
    const val OPENID = "openid"
  }
}
