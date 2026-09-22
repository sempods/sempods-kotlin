package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.Redirectable
import org.sempods.pods.HostedPod
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer

/**
 * Minting the authorization code both browser routes end at.
 *
 * `/authorize` gets here when standing grants make a dialog unnecessary. The consent submission
 * gets here once the person's answer is written.
 */
class PodAuthorizationCodes @Inject internal constructor(
  private val authorizationCodeStore: AuthorizationCodeStore,
  private val podSignOut: PodSignOut,
) {

  /** @param session the sign-in this code is issued under, or `null` for the anonymous one. */
  internal fun issue(
    pod: HostedPod,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    target: Redirectable,
    state: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    via: PodCodeIssuance,
    consentGeneration: Long? = null,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodCodeResult {
    // Defense-in-depth: even if a code path reaches here without /authorize's PKCE check,
    // never mint an auth code for a dynamic (public) client without PKCE.
    if (clientId.startsWith(PodClientDirectory.DYNAMIC_PREFIX)) {
      if (codeChallenge.isNullOrBlank() || !Pkce.isSupportedMethod(codeChallengeMethod)) {
        return refused(
          target, OAuthErrorCode.INVALID_REQUEST,
          "PKCE (S256) is required for dynamic clients", state,
        )
      }
    }
    // Asked again, now that [consentGeneration] has been read. A sign-out landing between the session
    // read and that one moves the generation first, and the code would carry the moved generation and
    // redeem. The sign-out writes its instant before it moves the generation, so a code that could
    // carry the moved one finds the instant here.
    if (session != null && !podSignOut.sessionStands(pod.id, session)) {
      return refused(target, OAuthErrorCode.ACCESS_DENIED, "signed out", state)
    }
    val code = authorizationCodeStore.issue(
      realm = pod.name,
      clientId = clientId,
      subject = webId,
      scopes = scopes,
      redirectUri = target.uri,
      codeChallenge = codeChallenge,
      codeChallengeMethod = codeChallengeMethod,
      consentGeneration = consentGeneration,
    )

    logger.info {
      "[${via.tag}] Authorization code issued: pod='${pod.name}', clientId='$clientId', " +
          "webId='$webId', scopes=${scopes.size}"
    }
    // R6: terminal audit line for the success path. Pairs with the `outcome=start`
    // entry at the top of authorize() (and with consent-submission requests, which
    // also funnel through this helper).
    logger.info {
      "[oauth/authorize-audit] outcome=issued_code pod='${pod.name}' " +
          "client_id='$clientId' web_id='$webId' scopes=${scopes.size} " +
          "via='${via.tag}'"
    }
    return PodCodeResult.Minted(code = code, target = target, state = state)
  }

  private fun refused(
    target: Redirectable,
    error: OAuthErrorCode,
    description: String,
    state: String?,
  ): PodCodeResult =
    PodCodeResult.Refused(OAuthErrorDelivery.Redirect(target, error, description, state))

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/** What minting a code answers. Each flow turns it into the answer its own route speaks. */
internal sealed interface PodCodeResult {

  /** Where the code goes and what travels with it. The adapter builds the address. */
  data class Minted(val code: String, val target: Redirectable, val state: String?) : PodCodeResult

  /** No PKCE for a dynamic client, or the person signed out. */
  data class Refused(val delivery: OAuthErrorDelivery) : PodCodeResult
}

/** Which of the four ways to an authorization code was taken. Both log lines name it as `via=`. */
internal enum class PodCodeIssuance(val tag: String) {

  /** `scope=public-read&prompt=none` with nobody signed in. The one code with no person behind it. */
  ANONYMOUS_PUBLIC_READ("oauth/public-read/anon"),

  /** Grants stood and the person had answered once, so no dialog was shown. */
  AUTO_GRANT("oauth/auto-grant"),

  /** The dialog was submitted. */
  CONSENT("oauth/consent"),

  /**
   * An installation dialog was submitted. Apart from [CONSENT] because the code it mints carries a
   * one-shot authority and no grant, which is what an audit trail wants to be able to count.
   */
  INSTALLATION("oauth/installation"),
}
