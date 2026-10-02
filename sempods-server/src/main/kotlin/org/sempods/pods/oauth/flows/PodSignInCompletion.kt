package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.PendingLogin
import org.sempods.auth.PodIdentityProvider
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Secrets
import org.sempods.pods.HostedPod
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.SessionPrincipal
import java.io.IOException
import java.time.Instant

/**
 * Completes a sign-in [PodSignIn] parked: the id-server's answer at `oidc/callback`, turned into a
 * session and the request it resumes.
 *
 * Everything of substance is server-side: `state` names the parked request, and the identity is
 * fetched from the id-server's token endpoint with a verifier that never travelled through the
 * browser and checked against the nonce the sign-in sent. What arrived at the callback is a code,
 * which is worth nothing without both.
 *
 * The parked request is then re-entered, and re-validated from scratch — it may have waited fifteen
 * minutes, and the pod's clients and grants can have moved in that time.
 */
class PodSignInCompletion @Inject internal constructor(
  private val loginStateStore: PodLoginStateStore,
  private val identityProvider: PodIdentityProvider,
  private val podTokenIssuer: PodTokenIssuer,
  private val podAuthorizeFlow: PodAuthorizeFlow,
  private val podServiceConsentFlow: PodServiceConsentFlow,
) {

  internal fun complete(pod: HostedPod, callback: PodSignInCallback): PodSignInResult {
    // Consumed first and unconditionally: a replayed callback must find nothing, whether it
    // carries a code, an error, or neither.
    val pending = callback.state?.trim()?.takeIf { it.isNotBlank() }?.let { loginStateStore.consume(it) }
    if (pending == null || pending.pod != pod.name) return PodSignInResult.Refused(PodSignInRefusal.UNKNOWN_STATE)

    // Login-CSRF / session fixation: this callback must complete in the SAME browser that started
    // the sign-in, because it is about to establish a session here. Without it an attacker starts
    // their own login, gets the callback URL opened in somebody else's browser, and that browser
    // comes away signed in as the attacker. Checked before the code is exchanged — a callback
    // opened in the wrong browser must cost nothing.
    if (!Secrets.matches(callback.presentedPin, pending.browserPin)) {
      logger.warn {
        "[oauth/authorize] login callback rejected: browser pin ${if (callback.presentedPin == null) "absent" else "mismatch"} " +
            "(pod='${pod.name}', clientId='${pending.clientId}')"
      }
      return PodSignInResult.Refused(PodSignInRefusal.OTHER_BROWSER)
    }

    val error = callback.error
    if (error != null) {
      logger.info {
        "[oauth/authorize-audit] outcome=login_failed pod='${pod.name}' " +
            "client_id='${pending.clientId}' error='$error'"
      }
      // The upstream provider's own verdict, translated for this pod's client.
      val upstream = OAuthErrors.fromUpstream(error, callback.errorDescription)
      return PodSignInResult.Failed(pending, upstream.code, upstream.description)
    }
    // Neither an error nor a code: nobody refused anything, the callback is malformed. `server_error`
    // rather than `access_denied`, so a client does not record a decision that was never made.
    val authorizationCode = callback.code?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodSignInResult.Failed(pending, OAuthErrorCode.SERVER_ERROR, "no authorization code")

    val verified = try {
      identityProvider.relyingParty(pod.name)
        .completeAuthorization(authorizationCode, pending.codeVerifier, pending.nonce)
    } catch (e: Exception) {
      // Transient by evidence rather than by guess: an `IOException` anywhere in the cause chain is
      // the transport saying it could not reach the identity service — a connect or read failure,
      // not a verdict. That is `temporarily_unavailable`, which a client may retry. Anything else
      // reaching here is this server's own fault and says so.
      val unreachable = generateSequence(e as Throwable?) { it.cause }.any { it is IOException }
      val failureClass =
        if (unreachable) OAuthErrorCode.TEMPORARILY_UNAVAILABLE else OAuthErrorCode.SERVER_ERROR
      logger.warn(e) {
        "[oauth/authorize] id-server token exchange failed: pod='${pod.name}', " +
            "clientId='${pending.clientId}', answered='${failureClass.code}'"
      }
      return PodSignInResult.Failed(pending, failureClass, "login failed")
    }

    logger.info {
      "[oauth/authorize] login completed: pod='${pod.name}', clientId='${pending.clientId}', " +
          "webId='${verified.webId}'"
    }
    // Remembered on this pod's own origin, so the next authorization needs no round trip and
    // `prompt=none` has something to answer with. One instant for both: the session's `auth_time`
    // and the principal this request runs under describe the same sign-in, and two `Instant.now()`
    // calls would date it twice.
    val authTime = Instant.now()
    val aliases = identityProvider.aliasesOf(verified)
    val sessionToken = podTokenIssuer.issueSession(
      pod.name, verified.webId, aliases, authTime, PodTokenIssuer.SESSION_TTL_SECONDS,
    )
    val principal = SessionPrincipal(verified.webId, aliases, authTime)
    return PodSignInResult.SignedIn(sessionToken, resume(pod, pending, principal))
  }

  private fun resume(pod: HostedPod, pending: PendingLogin, session: SessionPrincipal): PodSignInResumed =
    if (pending.serviceConsent) {
      PodSignInResumed.ServiceConsent(
        podServiceConsentFlow.open(
          pod = pod,
          request = PodServiceConsentRequest(
            clientId = pending.clientId,
            redirectUri = pending.redirectUri,
            state = pending.clientState,
          ),
          session = session,
        ),
      )
    } else {
      PodSignInResumed.Authorize(
        podAuthorizeFlow.authorize(
          pod = pod,
          request = PodAuthorizeRequest(
            clientId = pending.clientId,
            redirectUri = pending.redirectUri,
            state = pending.clientState,
            // What the flow wrote when it parked the request, after reading it: nothing to refuse.
            terms = PodAuthorizeTerms.Read(
              responseType = setOf("code"),
              codeChallenge = pending.codeChallenge,
              codeChallengeMethod = pending.codeChallengeMethod,
              prompt = OAuthSyntax.parsePrompt(pending.prompt),
              scopes = OAuthSyntax.parseScope(pending.scope),
            ),
          ),
          session = session,
        ),
      )
    }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * What the id-server sent the browser back with, and the login pin the browser presented beside it
 * — `null` where it presented none.
 */
internal data class PodSignInCallback(
  val state: String?,
  val code: String?,
  val error: String?,
  val errorDescription: String?,
  val presentedPin: String?,
)

/** What a callback came to. */
internal sealed interface PodSignInResult {

  /** The callback cannot be tied to a sign-in this browser started. Nothing was exchanged. */
  data class Refused(val refusal: PodSignInRefusal) : PodSignInResult

  /**
   * The sign-in failed, to be reported where [pending] was validated. [description] may carry the
   * provider's own text, which [OAuthErrors.fromUpstream] holds to RFC 6749 §4.1.2.1's character set.
   */
  data class Failed(val pending: PendingLogin, val error: OAuthErrorCode, val description: String) : PodSignInResult

  /** Signed in: the session token to remember, and what the resumed request answered. */
  data class SignedIn(val sessionToken: String, val resumed: PodSignInResumed) : PodSignInResult
}

internal enum class PodSignInRefusal {
  /** No parked sign-in under that `state` on this pod: unknown, expired, or already used. */
  UNKNOWN_STATE,

  /** The browser did not present the pin the sign-in was parked with. */
  OTHER_BROWSER,
}

/** The request a sign-in resumed, as its own flow answered it. */
internal sealed interface PodSignInResumed {
  data class Authorize(val result: PodAuthorizeResult) : PodSignInResumed
  data class ServiceConsent(val result: PodServiceConsentResult) : PodSignInResumed
}
