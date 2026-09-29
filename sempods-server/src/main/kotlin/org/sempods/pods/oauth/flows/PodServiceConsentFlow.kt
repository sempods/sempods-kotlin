package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Redirectable
import org.sempods.auth.core.RedirectUri
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.GrantReplacement
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistrationId
import java.net.URI
import java.time.Instant

/**
 * The service consent: a service sends the pod owner here, and the owner decides which contexts it
 * reaches — one registered at the pod (`svc:`) or one the operator provisioned. What a service sends and learns is `sempods-server/docs/auth/service-clients.md`
 * §"Consent".
 *
 * **Nothing is delivered to the service.** Confirming answers `state` alone, cancelling
 * `access_denied`, and every other outcome is a page for the person. The service learns what it may
 * do from the token endpoint and `GET /contexts`, the way it uses that access.
 *
 * **Confirming replaces the service's grants** through [ConsentSelection], at the version the screen
 * was rendered at ([ConsentTransactionStore.ServiceRecipient]), and activates a provisional
 * registration in the same write. An empty confirmation removes every grant and activates too.
 * Only the pod owner approves.
 */
class PodServiceConsentFlow @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val podGrantsFacade: PodGrantsFacade,
  private val consentTransactionStore: ConsentTransactionStore,
  private val consentSelection: ConsentSelection,
  private val signIn: PodSignIn,
  private val podSignOut: PodSignOut,
) {

  /** Renders the dialog, parks the request behind a sign-in, or answers with a page. */
  internal fun open(
    pod: HostedPod,
    request: PodServiceConsentRequest,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodServiceConsentResult {
    // Both asked before any sign-in, and both answered to the browser: until they hold, nothing
    // names an address this service may be sent to.
    val registration = request.clientId?.trim()?.takeIf { it.isNotBlank() }
      ?.let { serviceClients.find(pod.id, it) }
      ?: return PodServiceConsentResult.Refused(PodServiceConsentRefusal.UNKNOWN_SERVICE)
    val redirectUri = request.redirectUri?.trim()?.takeIf { it.isNotBlank() }
    val target = redirectUri?.let {
      redirectTarget(registration, it) ?: return PodServiceConsentResult.Refused(PodServiceConsentRefusal.REDIRECT_URI_NOT_ALLOWED)
    }
    val state = suppliedState(request.state)

    if (session == null) return parkForSignIn(pod, registration.clientId, redirectUri, state)
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)
    if (!podGrantsFacade.isPodOwner(pod, identity.allUris)) {
      logger.info {
        "[service-clients/consent] Not the owner: pod='${pod.name}', serviceClient='${registration.clientId}', " +
            "webId='${identity.webId}'"
      }
      return PodServiceConsentResult.Refused(PodServiceConsentRefusal.NOT_OWNER)
    }

    // Pre-ticked from what the service holds now. The request suggests no rows.
    val contexts = consentSelection.rows(pod, podGrantsFacade.resolveUserGrants(pod, identity.allUris), registration.scopes)
    val csrf = consentTransactionStore.issue(
      pod = pod.name,
      webId = identity.webId,
      consentGeneration = null,
      offeredFeatureScopes = emptySet(),
      disconnects = 0,
      binding = ConsentTransactionStore.Binding(
        clientId = registration.clientId,
        redirectUri = target?.uri,
        state = state,
        codeChallenge = null,
        codeChallengeMethod = null,
        offeredContexts = contexts.mapTo(mutableSetOf()) { it.uri },
        publicReadOffered = false,
        contextCreationOffered = true,
        service = ConsentTransactionStore.ServiceRecipient(registration.id.value, registration.grantsVersion),
      ),
    )
    logger.info {
      "[service-clients/consent] Showing service consent: pod='${pod.name}', " +
          "serviceClient='${registration.clientId}', webId='${identity.webId}', version=${registration.grantsVersion}"
    }
    return PodServiceConsentResult.Screen(
      PodServiceConsentScreen(
        podName = pod.name,
        podBaseUrl = pod.baseUrl,
        clientId = registration.clientId,
        clientName = registration.label,
        registeredAt = registration.createdAt,
        activationExpiresAt = registration.pendingUntil,
        held = registration.scopes.sorted(),
        contexts = contexts,
        csrfToken = csrf,
        webId = identity.webId,
      ),
    )
  }

  /** Redeems the dialog. Everything but the ticked rows comes from the screen's transaction. */
  internal fun submit(
    pod: HostedPod,
    form: PodServiceConsentForm,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodServiceConsentResult {
    if (session == null) return PodServiceConsentResult.Refused(PodServiceConsentRefusal.SESSION_EXPIRED)
    val presented = form.csrf?.trim()?.takeIf { it.isNotBlank() }
    val transaction = presented?.let { consentTransactionStore.consume(it) }
    if (transaction == null || transaction.pod != pod.name || transaction.webId != session.webId) {
      logger.warn {
        "[service-clients/consent] rejected: token ${if (presented == null) "absent" else "unknown, spent or not this session's"} " +
            "(pod='${pod.name}')"
      }
      return PodServiceConsentResult.Refused(PodServiceConsentRefusal.FORM_EXPIRED)
    }
    val binding = transaction.binding
    val service = binding?.service
    val postedClientId = form.clientId?.trim()?.takeIf { it.isNotBlank() }
    if (binding == null || service == null || (postedClientId != null && postedClientId != binding.clientId)) {
      logger.warn {
        "[service-clients/consent] rejected: the form answers another screen " +
            "(pod='${pod.name}', clientId='${binding?.clientId}', posted='${LogSafeText.of(postedClientId ?: "(none)")}')"
      }
      return PodServiceConsentResult.Refused(PodServiceConsentRefusal.FORM_MISMATCH)
    }
    val clientId = binding.clientId
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)
    if (form.action?.trim() == SIGN_OUT_ACTION) {
      podSignOut.signOut(pod.id, pod.name, identity.allUris)
      return PodServiceConsentResult.SignedOut
    }

    // Ahead of a cancel too: the service hears only the owner's decision.
    if (!podGrantsFacade.isPodOwner(pod, identity.allUris)) return PodServiceConsentResult.Refused(PodServiceConsentRefusal.NOT_OWNER)

    // Proven again against the registration the screen was rendered for. One removed since, or
    // re-created under the same identifier, is sent nothing.
    val registration = serviceClients.find(pod.id, clientId)?.takeIf { it.id.value == service.registrationId }
    val target = registration?.let { redirectTarget(it, binding.redirectUri) }
    if (form.action?.trim() == CANCEL_ACTION) {
      logger.info { "[service-clients/consent] Cancelled: pod='${pod.name}', serviceClient='$clientId'" }
      return PodServiceConsentResult.Answered(PodServiceConsentOutcome.CANCELLED, target, binding.state)
    }

    val recipient = GrantRecipient.Service(ServiceClientRegistrationId(service.registrationId), clientId, service.grantsVersion)
    val offer = ConsentSelection.Offer(binding.offeredContexts, publicRead = false, contextCreation = true)
    val submission = ConsentSelection.Submission(
      form.scopes.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.toSet(),
      form.newContexts,
      form.newContextScopes,
    )
    // The empty confirmation removes every grant and activates the registration all the same. A
    // selection none of whose rows the owner still reaches confirms nothing either.
    fun confirmNothing() = podGrantsFacade.replaceGrants(pod, recipient, emptySet(), grantedBy = identity.webId)
    val (replacement, created) = when (val parsed = consentSelection.parse(pod, submission, offer)) {
      ConsentSelection.Parsed.Empty -> confirmNothing() to emptyList()
      is ConsentSelection.Parsed.Refused -> return PodServiceConsentResult.Refused(PodServiceConsentRefusal.SELECTION_REFUSED)
      is ConsentSelection.Parsed.Selection ->
        consentSelection.apply(pod, parsed, recipient, approverUris = identity.allUris, approver = identity.webId)
          .let { (it.replacement ?: confirmNothing()) to it.created }
    }

    return when (replacement) {
      is GrantReplacement.Replaced -> {
        logger.info {
          "[service-clients/consent] Confirmed: pod='${pod.name}', serviceClient='$clientId', webId='${identity.webId}', " +
              "scopes='${LogSafeText.of(replacement.granted.sorted().joinToString(" "))}'"
        }
        PodServiceConsentResult.Answered(PodServiceConsentOutcome.CONFIRMED, target, binding.state)
      }
      GrantReplacement.Conflict -> {
        logger.warn {
          "[service-clients/consent] rejected: the grants changed while the dialog was open " +
              "(pod='${pod.name}', serviceClient='$clientId', version=${service.grantsVersion})"
        }
        PodServiceConsentResult.Refused(PodServiceConsentRefusal.CHANGED_MEANWHILE, created)
      }
      GrantReplacement.NotFound -> {
        logger.warn {
          "[service-clients/consent] rejected: the registration went while the dialog was open " +
              "(pod='${pod.name}', serviceClient='$clientId')"
        }
        PodServiceConsentResult.Refused(PodServiceConsentRefusal.SERVICE_REMOVED, created)
      }
    }
  }

  private fun parkForSignIn(
    pod: HostedPod,
    clientId: String,
    redirectUri: String?,
    state: String?,
  ): PodServiceConsentResult {
    val parked = signIn.park(pod, prompt = null) { codeVerifier, nonce, browserPin ->
      PodLoginStateStore.Pending(
        pod = pod.name,
        clientId = clientId,
        redirectUri = redirectUri,
        clientState = state,
        scope = null,
        prompt = null,
        codeChallenge = null,
        codeChallengeMethod = null,
        codeVerifier = codeVerifier,
        nonce = nonce,
        browserPin = browserPin,
        serviceConsent = true,
      )
    } ?: return PodServiceConsentResult.Refused(PodServiceConsentRefusal.IDENTITY_PROVIDER_UNAVAILABLE)
    logger.info { "[service-clients/consent] Redirecting to login: pod='${pod.name}', serviceClient='$clientId'" }
    return PodServiceConsentResult.Login(parked.authorizationUrl, parked.state, parked.browserPin)
  }

  /**
   * [redirectUri] where the service registered it, compared as `/authorize` compares a `dyn:`
   * client's: a loopback address matches on any port (RFC 8252 §7.3).
   */
  private fun redirectTarget(registration: ServiceClientRegistration, redirectUri: String?): Redirectable? =
    OAuthErrors.redirectTargetFor(
      { _, uri -> RedirectUri.isValid(uri) && RedirectUri.matchesRegistered(uri, registration.redirectUris) },
      registration.clientId,
      redirectUri,
    )

  private companion object {
    private val logger = KotlinLogging.logger {}

    private const val CANCEL_ACTION = "cancel"
    private const val SIGN_OUT_ACTION = "signout"
  }
}

/** The consent URL's parameters as the browser sent them. Anything else is ignored. */
internal data class PodServiceConsentRequest(
  val clientId: String?,
  val redirectUri: String?,
  val state: String?,
)

/**
 * The dialog's answer as the browser posted it.
 *
 * @param clientId not rendered; a value posted by hand must name the screen's service.
 * @param action `cancel`, `signout`, or `null` for the confirmation.
 */
internal data class PodServiceConsentForm(
  val csrf: String?,
  val clientId: String?,
  val scopes: List<String>?,
  val newContexts: List<String>?,
  val newContextScopes: List<String>?,
  val action: String?,
)

/**
 * Everything the service consent shows.
 *
 * @param clientName the name the service registered with: its own claim, which any registration may
 *   make. [clientId] and [registeredAt] are the pod's facts beside it.
 * @param activationExpiresAt when a provisional registration expires, `null` on an active one.
 * @param held the grants the service holds now.
 */
internal data class PodServiceConsentScreen(
  val podName: String,
  val podBaseUrl: String,
  val clientId: String,
  val clientName: String?,
  val registeredAt: Instant,
  val activationExpiresAt: Instant?,
  val held: List<String>,
  val contexts: List<PodConsentContext>,
  val csrfToken: String,
  val webId: String,
)

/** What the service consent answers. */
internal sealed interface PodServiceConsentResult {

  data class Screen(val screen: PodServiceConsentScreen) : PodServiceConsentResult

  /** Parked behind a sign-in; see [PodAuthorizeResult.Login]. */
  data class Login(val authorizationUrl: String, val state: String, val browserPin: String) : PodServiceConsentResult

  /**
   * The owner decided. With a [target] the browser returns there with `state` (and `access_denied`
   * on a cancel); without one the pod shows the finish page.
   */
  data class Answered(val outcome: PodServiceConsentOutcome, val target: Redirectable?, val state: String?) :
    PodServiceConsentResult

  /** The person signed out on the dialog. The service is told nothing. */
  data object SignedOut : PodServiceConsentResult

  /** A page and no redirect. [created] names contexts a refused submission created all the same. */
  data class Refused(val reason: PodServiceConsentRefusal, val created: List<URI> = emptyList()) : PodServiceConsentResult
}

internal enum class PodServiceConsentOutcome { CONFIRMED, CANCELLED }

/** Why the service consent answered with a page. */
internal enum class PodServiceConsentRefusal {
  /** Not a live service registration on this pod. */
  UNKNOWN_SERVICE,
  REDIRECT_URI_NOT_ALLOWED,
  IDENTITY_PROVIDER_UNAVAILABLE,
  NOT_OWNER,
  SESSION_EXPIRED,

  /** The one-time ticket is absent, spent or another session's. */
  FORM_EXPIRED,

  /** The ticket belongs to another screen: a delegated one, or another service's. */
  FORM_MISMATCH,

  /** A selection the dialog could not have produced. Nothing was written. */
  SELECTION_REFUSED,

  /** The service's grants changed after the screen was rendered. Nothing was granted. */
  CHANGED_MEANWHILE,

  /** The registration was removed after the screen was rendered. */
  SERVICE_REMOVED,
}
