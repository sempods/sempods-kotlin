package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.PersonIdentity
import org.sempods.auth.PodLoginStateStore
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Redirectable
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.ServiceClientGrantTransactionStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.pods.oauth.serviceclients.ServiceClientRegistration
import java.time.Instant

/**
 * The grant consent: the pod owner gives an installed service client contexts. What a caller sends
 * and receives is `docs/auth/oauth.md` §"Granting it contexts"; what the transaction binds is
 * [ServiceClientGrantTransactionStore].
 *
 * Only the pod owner approves, and only what [PodGrantsFacade.resolveUserGrants] lets them delegate.
 * That ceiling is asked when the dialog opens, when it is redeemed, and once more after the write.
 * Every refusal after the redirect is proven reads as one `access_denied`.
 */
class PodServiceClientGrantFlow @Inject internal constructor(
  private val serviceClients: PodServiceClientStore,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val transactions: ServiceClientGrantTransactionStore,
  private val signIn: PodSignIn,
) {

  /** Renders the dialog, parks the request behind a sign-in, or refuses. */
  internal fun open(
    pod: HostedPod,
    request: PodServiceClientGrantRequest,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodServiceClientGrantResult {
    val clientState = suppliedState(request.state)

    // Nothing is delivered to the redirect until it is proven to be the named client's.
    val redirectUri = request.redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.MISSING_REDIRECT_URI)
    val clients = PodClientDirectory.of(pod.id, dynamicClientStore)
    val requesterClientId = when (val client = clients.identify(request.clientId)) {
      is PodClientIdentity.Known -> client.clientId
      PodClientIdentity.Unregistered -> return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.UNREGISTERED_CLIENT)
      PodClientIdentity.Malformed -> return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.MALFORMED_CLIENT_ID)
    }
    val target = OAuthErrors.redirectTargetFor(clients, requesterClientId, redirectUri)
      ?: return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.REDIRECT_URI_NOT_ALLOWED)

    val serviceClientId = request.serviceClient?.trim()?.takeIf { it.isNotBlank() }
      ?: return failed(target, OAuthErrorCode.INVALID_REQUEST, "service_client is required", clientState)
    val requested = OAuthSyntax.parseScope(request.scope)
    if (requested.isEmpty()) {
      return failed(target, OAuthErrorCode.INVALID_SCOPE, "name the contexts the service is to reach", clientState)
    }
    // Before any sign-in: a malformed request is the caller's to fix.
    val ungrantable = serviceClients.ungrantable(pod, requested)
    if (ungrantable.isNotEmpty()) {
      return failed(
        target, OAuthErrorCode.INVALID_SCOPE,
        "a service client holds context scopes only: ${ungrantable.keys.sorted().joinToString(" ")}", clientState,
      )
    }

    if (session == null) {
      return parkForSignIn(pod, requesterClientId, redirectUri, clientState, request.scope, serviceClientId)
    }
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)

    val registration = grantable(pod, identity, serviceClientId, requested)
      ?: return denied(target, clientState, pod, serviceClientId, "not grantable by this person at open")

    val csrf = transactions.issue(
      ServiceClientGrantTransactionStore.Transaction(
        pod = pod.name,
        webId = identity.webId,
        signedInAt = session.authTime.epochSecond,
        serviceClientId = registration.clientId,
        registrationId = registration.id.value,
        scopes = requested,
        requesterClientId = requesterClientId,
        redirectUri = redirectUri,
        state = clientState,
      ),
    )
    logger.info {
      "[service-clients/grant] Showing grant consent: pod='${pod.name}', " +
          "serviceClient='${registration.clientId}', requester='$requesterClientId', " +
          "webId='${identity.webId}', scopes='${LogSafeText.of(requested.sorted().joinToString(" "))}'"
    }
    return PodServiceClientGrantResult.Screen(
      PodServiceClientGrantScreen(
        podName = pod.name,
        podBaseUrl = pod.baseUrl,
        requesterName = clientDisplayName(dynamicClientStore.registrationOf(pod.id, requesterClientId), requesterClientId),
        serviceClientId = registration.clientId,
        serviceLabel = registration.label ?: registration.clientId,
        registeredAt = registration.createdAt,
        requested = requested.sorted(),
        held = registration.scopes.sorted(),
        csrfToken = csrf,
        webId = identity.webId,
      ),
    )
  }

  /** Redeems the dialog. Everything but the ticked rows comes from the transaction. */
  internal fun submit(
    pod: HostedPod,
    form: PodServiceClientGrantForm,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodServiceClientGrantResult {
    if (session == null) return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.SESSION_EXPIRED)
    val presented = form.csrf?.trim()?.takeIf { it.isNotBlank() }
    val transaction = presented?.let { transactions.consume(it) }
    if (
      transaction == null ||
      transaction.pod != pod.name ||
      transaction.webId != session.webId ||
      transaction.signedInAt != session.authTime.epochSecond
    ) {
      logger.warn {
        "[service-clients/grant] rejected: transaction ${if (presented == null) "absent" else "unknown, spent or not this session's"} " +
            "(pod='${pod.name}')"
      }
      return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.FORM_EXPIRED)
    }

    // Proven again: the requesting client may have been cleared while the dialog stood open.
    val clients = PodClientDirectory.of(pod.id, dynamicClientStore)
    val target = OAuthErrors.redirectTargetFor(clients, transaction.requesterClientId, transaction.redirectUri)
      ?: return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.REDIRECT_URI_NOT_ALLOWED)
    val state = transaction.state

    if (form.serviceClient?.trim() != transaction.serviceClientId) {
      logger.warn {
        "[service-clients/grant] rejected: form names '${LogSafeText.of(form.serviceClient ?: "(none)")}', " +
            "dialog showed '${transaction.serviceClientId}' (pod='${pod.name}')"
      }
      return failed(target, OAuthErrorCode.INVALID_REQUEST, "this answer is for a different service", state)
    }

    if (form.action?.trim() == REFUSE_ACTION) {
      logger.info {
        "[service-clients/grant] Refused by owner: pod='${pod.name}', serviceClient='${transaction.serviceClientId}'"
      }
      return failed(target, OAuthErrorCode.ACCESS_DENIED, "the owner refused the grant", state)
    }

    val ticked = form.scopes.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (!transaction.scopes.containsAll(ticked)) {
      logger.warn {
        "[service-clients/grant] rejected: a scope the dialog did not offer " +
            "(pod='${pod.name}', serviceClient='${transaction.serviceClientId}', " +
            "extra='${LogSafeText.of((ticked - transaction.scopes).sorted().joinToString(" "))}')"
      }
      return failed(target, OAuthErrorCode.INVALID_SCOPE, "a scope this dialog did not offer", state)
    }
    if (ticked.isEmpty()) {
      return failed(target, OAuthErrorCode.ACCESS_DENIED, "the owner granted nothing", state)
    }

    // An approval must not restore authority lost while the dialog stood open, nor reach a
    // registration re-created under the same identifier.
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)
    val registration = grantable(pod, identity, transaction.serviceClientId, ticked)
    if (registration == null || registration.id.value != transaction.registrationId) {
      return denied(target, state, pod, transaction.serviceClientId, "no longer grantable at redemption")
    }
    if (!serviceClients.addScopes(pod, registration.clientId, registration.id, ticked)) {
      return denied(target, state, pod, transaction.serviceClientId, "revoked while being granted")
    }
    // After the write, against a context deletion racing it: both sides write before they read
    // (`PodFacade.removeContext` strips again once the registry row is gone).
    val lost = ticked - podGrantsFacade.resolveUserGrants(pod, identity.allUris)
    if (lost.isNotEmpty()) serviceClients.removeScopes(pod.id, registration.clientId, lost)
    val granted = ticked - lost
    if (granted.isEmpty()) {
      return denied(target, state, pod, transaction.serviceClientId, "its contexts went while being granted")
    }

    logger.info {
      "[service-clients/grant] Granted: pod='${pod.name}', serviceClient='${registration.clientId}', " +
          "requester='${transaction.requesterClientId}', webId='${identity.webId}', " +
          "scopes='${LogSafeText.of(granted.sorted().joinToString(" "))}'"
    }
    return PodServiceClientGrantResult.Granted(target, state, granted)
  }

  /**
   * The owner-installed registration, where this person owns the pod and may delegate every one of
   * [scopes] now; otherwise `null`.
   */
  private fun grantable(
    pod: HostedPod,
    identity: PersonIdentity,
    serviceClientId: String,
    scopes: Set<String>,
  ): ServiceClientRegistration? {
    if (!podGrantsFacade.isPodOwner(pod, identity.allUris)) return null
    val registration = serviceClients.find(pod.id, serviceClientId)?.takeIf { it.installed } ?: return null
    val delegable = podGrantsFacade.resolveUserGrants(pod, identity.allUris)
    return registration.takeIf { delegable.containsAll(scopes) }
  }

  private fun parkForSignIn(
    pod: HostedPod,
    requesterClientId: String,
    redirectUri: String,
    state: String?,
    scope: String?,
    serviceClientId: String,
  ): PodServiceClientGrantResult {
    val parked = signIn.park(pod, prompt = null) { codeVerifier, nonce, browserPin ->
      PodLoginStateStore.Pending(
        pod = pod.name,
        clientId = requesterClientId,
        redirectUri = redirectUri,
        clientState = state,
        scope = scope,
        prompt = null,
        codeChallenge = null,
        codeChallengeMethod = null,
        codeVerifier = codeVerifier,
        nonce = nonce,
        browserPin = browserPin,
        serviceClient = serviceClientId,
      )
    } ?: return PodServiceClientGrantResult.Refused(PodServiceClientGrantRefusal.IDENTITY_PROVIDER_UNAVAILABLE)
    logger.info {
      "[service-clients/grant] Redirecting to login: pod='${pod.name}', requester='$requesterClientId'"
    }
    return PodServiceClientGrantResult.Login(parked.authorizationUrl, parked.state, parked.browserPin)
  }

  /** The one `access_denied` every refusal reads as. [why] goes to the log only. */
  private fun denied(
    target: Redirectable,
    state: String?,
    pod: HostedPod,
    serviceClientId: String,
    why: String,
  ): PodServiceClientGrantResult {
    logger.info {
      "[service-clients/grant] Denied: pod='${pod.name}', serviceClient='${LogSafeText.of(serviceClientId)}', reason='$why'"
    }
    return failed(target, OAuthErrorCode.ACCESS_DENIED, "the grant was not given", state)
  }

  private fun failed(
    target: Redirectable,
    error: OAuthErrorCode,
    description: String,
    state: String?,
  ): PodServiceClientGrantResult =
    PodServiceClientGrantResult.Error(OAuthErrorDelivery.Redirect(target, error, description, state))

  private companion object {
    private val logger = KotlinLogging.logger {}

    /** The dialog's refusal button. Anything else is an approval of the ticked rows. */
    private const val REFUSE_ACTION = "refuse"
  }
}

/** The dialog's parameters as the browser sent them. */
internal data class PodServiceClientGrantRequest(
  val clientId: String?,
  val redirectUri: String?,
  val state: String?,
  val serviceClient: String?,
  val scope: String?,
)

/** The dialog's answer as the browser posted it. */
internal data class PodServiceClientGrantForm(
  val csrf: String?,
  val serviceClient: String?,
  val scopes: List<String>?,
  val action: String?,
)

/**
 * Everything the grant dialog shows.
 *
 * @param serviceLabel the registering program's own text. [serviceClientId] and [registeredAt] are
 *   the pod's facts beside it, which a crafted registration cannot choose.
 * @param requested the rows offered, ticked; [held] what the service holds already.
 */
internal data class PodServiceClientGrantScreen(
  val podName: String,
  val podBaseUrl: String,
  val requesterName: String,
  val serviceClientId: String,
  val serviceLabel: String,
  val registeredAt: Instant,
  val requested: List<String>,
  val held: List<String>,
  val csrfToken: String,
  val webId: String,
)

/** What the grant consent answers. [Error] carries a proven redirect and [Refused] does not. */
internal sealed interface PodServiceClientGrantResult {

  data class Screen(val screen: PodServiceClientGrantScreen) : PodServiceClientGrantResult

  /** Parked behind a sign-in; see [PodAuthorizeResult.Login]. */
  data class Login(val authorizationUrl: String, val state: String, val browserPin: String) : PodServiceClientGrantResult

  /** The grants were written. [scopes] is what was added. */
  data class Granted(val target: Redirectable, val state: String?, val scopes: Set<String>) : PodServiceClientGrantResult

  data class Error(val delivery: OAuthErrorDelivery) : PodServiceClientGrantResult

  data class Refused(val reason: PodServiceClientGrantRefusal) : PodServiceClientGrantResult
}

/** Why the grant consent answered the browser directly instead of the caller. */
internal enum class PodServiceClientGrantRefusal {
  MISSING_REDIRECT_URI,
  UNREGISTERED_CLIENT,
  MALFORMED_CLIENT_ID,
  REDIRECT_URI_NOT_ALLOWED,
  IDENTITY_PROVIDER_UNAVAILABLE,
  SESSION_EXPIRED,
  FORM_EXPIRED,
}
