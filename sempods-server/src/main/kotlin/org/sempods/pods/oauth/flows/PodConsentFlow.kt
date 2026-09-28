package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Redirectable
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.grants.GrantRecipient
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.PodSignOut
import org.sempods.pods.oauth.PodTokenIssuer

/**
 * What a pod does with the consent dialog coming back: decide whether this submission may be acted
 * on at all, write what the person chose, and finish the authorization it belongs to.
 *
 * **Every decision here is the pod's, and none of them is HTTP.** The endpoint binds the form and
 * renders the [PodConsentResult] this hands back.
 *
 * **The submission is the authoritative new state**, and clearing it is the extreme case of that —
 * see [endAuthorization].
 *
 * **It answers the screen that was rendered.** The client, redirect, `state`, PKCE challenge and
 * offered rows are read from the screen's transaction ([ConsentTransactionStore.Binding]). A form
 * naming another request is [PodConsentRefusal.FORM_MISMATCH]; a selection the dialog could not have
 * produced is refused by [ConsentSelection] with nothing written. What each submission is answered
 * is tabled in `docs/auth/oauth.md` §"Authorize flow (overview)".
 */
class PodConsentFlow @Inject internal constructor(
  private val codes: PodAuthorizationCodes,
  private val dynamicClientStore: DynamicClientStore,
  private val podFacade: PodFacade,
  private val podGrantsFacade: PodGrantsFacade,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val consentTransactionStore: ConsentTransactionStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val podSignOut: PodSignOut,
  private val appHoldings: PodAppHoldings,
  private val consentSelection: ConsentSelection,
) {

  internal fun submit(
    pod: HostedPod,
    form: PodConsentForm,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodConsentResult {
    // Two questions, two answers. The session says *who* is submitting; the transaction says
    // *which screen* this is, and that it has not been submitted before. Neither alone is enough:
    // a session-derived token would be the same on every screen the session outlives (so a stale
    // page could be replayed over a narrower consent), and a transaction alone could be lifted out of a
    // page and spent from another browser.
    //
    // Both come first, ahead of the client: the screen's transaction is where the request it
    // answers is recorded.
    if (session == null) return PodConsentResult.Refused(PodConsentRefusal.SESSION_EXPIRED)
    val presented = form.csrf?.trim()?.takeIf { it.isNotBlank() }
    val transaction = presented?.let { consentTransactionStore.consume(it) }
    if (transaction == null || transaction.pod != pod.name || transaction.webId != session.webId) {
      logger.warn {
        // On the normalised value: a form posting `csrf=` never reaches the store, so it is
        // absent rather than spent.
        "[oauth/consent] rejected: consent token ${if (presented == null) "absent" else "unknown, spent or not this session's"} " +
            "(pod='${pod.name}')"
      }
      return PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED)
    }
    val request = ConsentRequest.of(transaction.binding, form)
      ?: return PodConsentResult.Refused(PodConsentRefusal.FORM_MISMATCH).also {
        logger.warn {
          "[oauth/consent] rejected: the form names another request than its screen was rendered for " +
              "(pod='${pod.name}', clientId='${transaction.binding?.clientId}')"
        }
      }
    val clientState = request.state

    // Same split as `/authorize`: a consent form submitted after the registration was cleared is
    // not a malformed `client_id`, and telling the person it is sends them looking for a typo.
    // Asked again of a bound request too, because the registration can go while the page is open.
    val clients = PodClientDirectory.of(pod.id, dynamicClientStore)
    val normalizedClientId = when (val client = clients.identify(request.clientId)) {
      is PodClientIdentity.Known -> client.clientId
      PodClientIdentity.Unregistered -> return PodConsentResult.Refused(PodConsentRefusal.UNREGISTERED_CLIENT)
      PodClientIdentity.Malformed -> return PodConsentResult.Refused(PodConsentRefusal.MALFORMED_CLIENT_ID)
    }

    val normalizedRedirectUri = request.redirectUri
      ?: return PodConsentResult.Refused(PodConsentRefusal.MISSING_REDIRECT_URI)

    val redirectTarget = OAuthErrors.redirectTargetFor(clients, normalizedClientId, normalizedRedirectUri)
      ?: return PodConsentResult.Refused(PodConsentRefusal.REDIRECT_URI_NOT_ALLOWED)

    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)
    // What this screen put to the person, known as soon as the transaction is — the named actions
    // below are answered differently on an installation screen, which offers neither of them.
    val offeredPrivileged = transaction.offeredFeatureScopes

    // Ahead of the check below, which refuses a page rendered before this app was disconnected. That
    // check keeps an old page from writing grants back; a sign-out writes none, and refusing it would
    // leave the person signed in with no way out on the page in front of them.
    if (form.action?.trim() == SIGN_OUT_ACTION) {
      podSignOut.signOut(pod.id, pod.name, identity.allUris)
      return PodConsentResult.SignedOut(
        OAuthErrorDelivery.Redirect(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "signed out", clientState),
      )
    }

    // Leaving writes nothing either, so a stale page may leave too. `access_denied`, like the other
    // two ways out; the description `cancelled` tells the app the person left.
    if (form.action?.trim() == CANCEL_ACTION) {
      logger.info { "[oauth/consent] Cancelled: pod='${pod.name}', clientId='$normalizedClientId'" }
      return failed(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "cancelled", clientState)
    }

    // Single-use stops this page being posted twice; it says nothing about a *second* page opened
    // before the app was disconnected, which would submit its own older selection as the
    // authoritative new state and hand back everything the person just removed. So a page carries
    // what stood when it was rendered, and one from before a disconnect is refused.
    //
    // Only that case. Screens are allowed to coexist on purpose — `ConsentTransactionStore` says
    // why, and `two sign-ins running at once in one browser both complete` pins it — so a page
    // that is merely older than the current answer, on an authorization that still holds
    // something, submits as it always did. A page that would resurrect a disconnected app does
    // not.
    //
    // Asked as the count of endings rather than as a moved generation, because the generation moves
    // for things that remove nothing — an installation, a forced review — and an ordinary page open
    // beside one of those has lost nothing and must still submit.
    val standingDecision = consentDecisionStore.find(pod.id, normalizedClientId, listOf(identity.webId))
    // Read once: the disconnect below asks the same question, and two reads could disagree.
    val holdsAnything = appHoldings.holdsAnything(pod.id, normalizedClientId, identity.allUris)
    if (transaction.disconnects != (standingDecision?.disconnects ?: 0L)) {
      logger.info {
        "[oauth/consent] rejected: page rendered before the app was disconnected (pod='${pod.name}', " +
            "clientId='$normalizedClientId', renderedAfter=${transaction.disconnects}, " +
            "standing=${standingDecision?.disconnects ?: 0L})"
      }
      return PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED)
    }

    // Before anything is created. The form can carry a context the person typed, and choosing to
    // remove an app's access is not the moment to build one for it — they asked for the opposite of
    // an authorization. The empty-submission route to the same place is further down, because it
    // can only be recognised once the selection has been read.
    if (form.action?.trim() == DISCONNECT_ACTION) {
      // Not from an installation screen. It renders no way out — ending an authorization it is not
      // about is not one click's worth of decision — and every other field this form could carry
      // across from another screen is refused below. This is the destructive one.
      if (offeredPrivileged.isNotEmpty()) {
        return failed(
          redirectTarget, OAuthErrorCode.INVALID_REQUEST,
          "an installation screen does not end an app's access", clientState,
        )
      }
      return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)
    }

    val isOwner = podGrantsFacade.isPodOwner(pod, identity.allUris)

    val rawSubmitted = form.scopes
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?.toSet()
      ?: emptySet()

    // ── A privileged feature scope is the whole of its own screen ─────────
    // What was put to the person came from the transaction rather than from the form: it is the
    // server's own record of which dialog this is, and it is what tells an unticked installation
    // screen ("do not install") from an unticked ordinary one ("remove this app's access") below.
    val submittedPrivileged = rawSubmitted.intersect(PodScopeValidator.privilegedFeatureScopes)
    if (!offeredPrivileged.containsAll(submittedPrivileged)) {
      logger.warn {
        "[oauth/consent] rejected: a privileged feature scope this screen did not offer " +
            "(pod='${pod.name}', clientId='$normalizedClientId', " +
            "submitted='${submittedPrivileged.sorted().joinToString(" ")}')"
      }
      return failed(
        redirectTarget, OAuthErrorCode.INVALID_SCOPE,
        "'${submittedPrivileged.sorted().joinToString(" ")}' was not offered on this screen", clientState,
      )
    }
    if (offeredPrivileged.isNotEmpty()) {
      return privilegedAuthority(
        pod = pod,
        clientId = normalizedClientId,
        identity = identity,
        form = form,
        request = request,
        target = redirectTarget,
        state = clientState,
        session = session,
        offered = offeredPrivileged,
        submitted = submittedPrivileged,
        rawSubmitted = rawSubmitted,
        isOwner = isOwner,
      )
    }

    // ── The grant selection, which every consent dialog shares ────────────
    val offer = transaction.binding
      ?.let { ConsentSelection.Offer(it.offeredContexts, it.publicReadOffered, it.contextCreationOffered) }
      // A screen an older node rendered bound no rows (`ConsentTransactionStore`, rollout): what it
      // posts is taken as offered, [ConsentSelection.apply] still drops what the person cannot
      // delegate, and only an owner creates contexts.
      ?: ConsentSelection.Offer(
        contexts = rawSubmitted.mapTo(mutableSetOf()) { it.substringBeforeLast('#') },
        publicRead = true,
        contextCreation = isOwner,
      )
    val submission = ConsentSelection.Submission(rawSubmitted, form.newContexts, form.newContextScopes)
    val selection = when (val parsed = consentSelection.parse(pod, submission, offer)) {
      // Nothing ticked anywhere is the other way to ask for the way out.
      ConsentSelection.Parsed.Empty ->
        return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)
      is ConsentSelection.Parsed.Refused -> return failed(redirectTarget, parsed.error, parsed.description, clientState)
      is ConsentSelection.Parsed.Selection -> parsed
    }

    // `public-read` is an additive scope, combined with per-context ones and persisted as a grant so
    // prompt=none auto-grant works on later /authorize calls. It needs at least one public context
    // to mean anything; alone on a pod with none, there is nothing to authorize.
    if (selection.scopes == setOf(PUBLIC_READ_SCOPE) && selection.pending.isEmpty() &&
      podFacade.getPublicContexts(podName = pod.name).isEmpty()
    ) {
      return failed(
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
        "pod has no public-read contexts and no per-context scopes were selected", clientState,
      )
    }

    // The replace is complete — the checkbox submission is the authoritative new state, so a scope
    // the person unticked is revoked. The facade re-derives after writing, so an owner-level
    // revocation that landed meanwhile cannot leave an unbacked grant behind; what comes back is
    // what actually survived.
    val applied = consentSelection.apply(
      pod = pod,
      selection = selection,
      recipient = GrantRecipient.Delegation(clientId = normalizedClientId, webId = identity.webId, aliases = identity.allUris),
      approverUris = identity.allUris,
      approver = identity.webId,
    )
    // Every row ticked turned out to be one the person no longer holds. That is the empty
    // confirmation after all, reached late.
    val persistedScopes = applied.replacement?.granted
      ?: return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)

    if (persistedScopes.isEmpty()) {
      // Recoverable: the person's authority changed while they were deciding. `consent_required`
      // rather than `access_denied` — nobody refused anything, the basis simply moved.
      logger.warn {
        "[oauth/consent] Selection void — owner-level access changed during consent: " +
            "pod='${pod.name}', clientId='$normalizedClientId', webId='${identity.webId}', " +
            "created=${applied.created}"
      }
      return failed(
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
        "granted access changed while consenting; please re-authorize" +
            if (applied.created.isEmpty()) "" else "; the contexts created stay, private and without grants",
        clientState,
      )
    }

    // **The answer is written after the grants, and a run dying between them leaves the safe
    // half.** What survives is the selection the person just made, under the answer that stood
    // before it — so a narrowing takes effect, and the durability question keeps its previous
    // answer rather than acquiring one nobody gave. Writing the answer first inverts exactly that:
    // the old, wider grants would stand under a *new* generation, the narrowing silently lost and
    // the credentials it was meant to end still rotating.
    //
    // The pair this order can leave — grants with no answer beside them, on a first consent — is
    // harmless since a code carrying no generation is refused at the exchange: nothing redeems,
    // auto-grant needs a decision it does not have, and the next visit renders this dialog again.
    val decision = recordDecision(pod, normalizedClientId, identity, durable = form.durable)
    if (!form.durable) {
      // Withholding is not merely declining to extend: the families this authorization already has
      // would otherwise keep rotating, and the person would have changed nothing they can observe.
      val revoked = refreshTokenStore.revokeForUser(
        pod = pod.id,
        clientId = normalizedClientId,
        webIds = identity.allUris,
      )
      if (revoked > 0) {
        logger.info {
          "[oauth/consent] Durable connection withheld — refresh tokens revoked: " +
              "pod='${pod.name}', clientId='$normalizedClientId', webId='${identity.webId}', " +
              "revokedRows=$revoked"
        }
      }
    }

    logger.info {
      "[oauth/consent] Grants saved: pod='${pod.name}', clientId='$normalizedClientId', " +
          "webId='${identity.webId}', scopes=${persistedScopes.size}, public_read=${selection.publicRead}, " +
          "durable=${form.durable}, generation=${decision.generation}"
    }

    // Slim access token: context permissions are resolved server-side from the grant just
    // persisted, so only feature scopes (e.g. `public-read`) travel in the token.
    val tokenFeatureScopes = if (selection.publicRead) setOf(PUBLIC_READ_SCOPE) else emptySet()

    return codes.issue(
      pod = pod,
      clientId = normalizedClientId,
      webId = identity.webId,
      scopes = tokenFeatureScopes,
      target = redirectTarget,
      state = clientState,
      codeChallenge = request.codeChallenge,
      codeChallengeMethod = request.codeChallengeMethod,
      via = PodCodeIssuance.CONSENT,
      consentGeneration = decision.generation,
      session = session,
    ).asResult()
  }

  /**
   * What a privileged dialog's submission is worth.
   *
   * **It writes no grant.** The code carries the feature scope alone, and the app's standing grants
   * stay as they were — which is why this sits ahead of every path below that reads an empty
   * selection as a disconnect.
   *
   * The decision is still recorded, because a code carrying no generation is refused at the
   * exchange — but it answers nothing about how long this app stays connected. The screen has no
   * lifetime control, and writing `false` for a question nobody was asked would end a durable
   * family the app already holds. `PodConsentDecisionStore.recordWithoutLifetime` is that write.
   *
   * @param offered what the screen put to the person, which the refusals name. [submitted] is what
   *   came back of it, and an empty one is the person saying no.
   */
  private fun privilegedAuthority(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
    form: PodConsentForm,
    request: ConsentRequest,
    target: Redirectable,
    state: String?,
    session: PodTokenIssuer.SessionPrincipal,
    offered: Set<String>,
    submitted: Set<String>,
    rawSubmitted: Set<String>,
    isOwner: Boolean,
  ): PodConsentResult {
    // Authority first, then what the submission is shaped like, then what it says. The screen was
    // rendered for an owner; a session that stopped being one in between decides nothing here.
    val asked = offered.sorted().joinToString(" ")
    if (!isOwner) {
      return failed(target, OAuthErrorCode.INVALID_SCOPE, "'$asked' is the pod owner's to grant", state)
    }
    // An installation selects no data and creates no context. The request that opened this screen
    // was refused if it asked for both, and a submission that asks for both is refused here.
    if (rawSubmitted != submitted || !form.newContexts.isNullOrEmpty() || !form.newContextScopes.isNullOrEmpty()) {
      return failed(
        target, OAuthErrorCode.INVALID_SCOPE,
        "'$asked' cannot be combined with access to data", state,
      )
    }
    if (form.durable) {
      return failed(target, OAuthErrorCode.INVALID_SCOPE, "'$asked' is granted once and does not renew", state)
    }
    if (submitted.isEmpty()) {
      logger.info {
        "[oauth/consent] Privileged authority declined: pod='${pod.name}', clientId='$clientId', " +
            "webId='${identity.webId}'"
      }
      return failed(target, OAuthErrorCode.ACCESS_DENIED, "'$asked' declined", state)
    }

    val decision = recordDecisionWithoutLifetime(pod, clientId, identity)
    logger.info {
      "[oauth/consent] Privileged authority granted: pod='${pod.name}', clientId='$clientId', " +
          "webId='${identity.webId}', scopes='${submitted.sorted().joinToString(" ")}', " +
          "generation=${decision.generation}"
    }
    return codes.issue(
      pod = pod,
      clientId = clientId,
      webId = identity.webId,
      scopes = submitted,
      target = target,
      state = state,
      codeChallenge = request.codeChallenge,
      codeChallengeMethod = request.codeChallengeMethod,
      via = PodCodeIssuance.privileged(submitted.single()),
      consentGeneration = decision.generation,
      // Recorded here because here is where it is still known. `PodClientRegistration` asks
      // `isOwner` again against this set, an hour later and with no browser in front of it.
      subjectUris = identity.allUris.toSet(),
      session = session,
    ).asResult()
  }

  /**
   * The three ways to ask for the way out, answered once.
   *
   * The named action and a submission that ticks nothing mean the same thing, and both end the
   * authorization where there is one. Where there is none the answer stays the plain denial it
   * always was: reporting a disconnect of nothing is the same lie as reporting nothing when
   * something ended.
   */
  private fun endAuthorization(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
    target: Redirectable,
    state: String?,
    holdsAnything: Boolean,
  ): PodConsentResult =
    if (holdsAnything) {
      disconnectApp(pod, clientId, identity, target, state)
    } else {
      failed(target, OAuthErrorCode.ACCESS_DENIED, "no scopes selected", state)
    }

  /**
   * Write the answer under every URI that names this person, and hand back the one for the URI they
   * are signed in as.
   *
   * One document per URI rather than one per person, because that is how the rows this sits beside
   * are keyed — and because the alternative is worse than the duplication: a code issued while an
   * alias was the session identity carries that alias's generation, and only a document of its own
   * can move when the person answers again under their canonical WebID. Without that, the older
   * code would still compare equal and redeem against a consent that has been replaced.
   */
  private fun recordDecision(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
    durable: Boolean,
  ): PodConsentDecisionStore.Decision {
    val forSubject = consentDecisionStore.record(pod.id, clientId, identity.webId, durable)
    identity.allUris.filterNot { it == identity.webId }.forEach { alias ->
      consentDecisionStore.record(pod.id, clientId, alias, durable)
    }
    return forSubject
  }

  /**
   * [recordDecision] for the one write that ends what this app holds, which is what a stale page is
   * compared against.
   */
  private fun recordDisconnect(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
  ): PodConsentDecisionStore.Decision {
    val forSubject = consentDecisionStore.recordDisconnect(pod.id, clientId, identity.webId)
    identity.allUris.filterNot { it == identity.webId }.forEach { alias ->
      consentDecisionStore.recordDisconnect(pod.id, clientId, alias)
    }
    return forSubject
  }

  /**
   * [recordDecision] for a dialog that put no lifetime question to the person.
   *
   * Written under every URI that names them for the same reason the other one is.
   */
  private fun recordDecisionWithoutLifetime(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
  ): PodConsentDecisionStore.Decision {
    val forSubject = consentDecisionStore.recordWithoutLifetime(pod.id, clientId, identity.webId)
    identity.allUris.filterNot { it == identity.webId }.forEach { alias ->
      consentDecisionStore.recordWithoutLifetime(pod.id, clientId, alias)
    }
    return forSubject
  }

  /**
   * End what this app holds for this person.
   *
   * The grants go, the decision is written as a refusal — a silence would read as an authorization
   * that predates the control and be left alone, and the count it moves withdraws an installation or
   * management authority — and the refresh families are revoked, because withholding that is merely declining
   * to extend would leave the person's most emphatic gesture with nothing to show for it. The client is still told `access_denied`: the request really was
   * denied, and what changed is that the denial now has an effect.
   */
  private fun disconnectApp(
    pod: HostedPod,
    clientId: String,
    identity: PersonIdentity,
    target: Redirectable,
    state: String?,
  ): PodConsentResult {
    // Once per URI that names the person, because that is how the rows are keyed: an authorization
    // made under an alias is one this person can end, and `holdsAnything` already counted it.
    identity.allUris.forEach { uri ->
      podGrantsFacade.replaceGrants(
        pod = pod,
        recipient = GrantRecipient.Delegation(clientId = clientId, webId = uri, aliases = identity.allUris),
        selection = emptySet(),
        grantedBy = identity.webId,
      )
    }
    val decision = recordDisconnect(pod, clientId, identity)
    val revoked = refreshTokenStore.revokeForUser(pod.id, clientId, identity.allUris)
    logger.info {
      "[oauth/consent] App disconnected: pod='${pod.name}', clientId='$clientId', " +
          "webId='${identity.webId}', revokedRows=$revoked, generation=${decision.generation}"
    }
    return failed(target, OAuthErrorCode.ACCESS_DENIED, "app disconnected", state)
  }

  /** This route's answer to whatever minting a code said. */
  private fun PodCodeResult.asResult(): PodConsentResult = when (this) {
    is PodCodeResult.Minted -> PodConsentResult.Code(code, target, state)
    is PodCodeResult.Refused -> PodConsentResult.Error(delivery)
  }

  /** An error at the client's own address — the rule [OAuthErrorDelivery] states. */
  private fun failed(
    target: Redirectable,
    error: OAuthErrorCode,
    description: String,
    state: String?,
  ): PodConsentResult =
    PodConsentResult.Error(OAuthErrorDelivery.Redirect(target, error, description, state))

  private companion object {
    private val logger = KotlinLogging.logger {}

    /** The form's named way out, as the submit button sends it. */
    private const val DISCONNECT_ACTION = "disconnect"

    /** The form's sign-out: it ends everything the person holds on the pod. */
    private const val SIGN_OUT_ACTION = "signout"

    /** The form's way back to the app, which changes nothing. */
    private const val CANCEL_ACTION = "cancel"
  }
}

/**
 * The authorization request a consent submission answers.
 *
 * Read from the screen's transaction where it was bound there, and from the form only where it was
 * not — a transaction an older node wrote, accepted during a rollout (`ConsentTransactionStore`).
 */
private data class ConsentRequest(
  val clientId: String?,
  val redirectUri: String?,
  val state: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
) {
  companion object {

    /**
     * @return the bound request, or `null` where the form names a different one. The form no longer
     *   renders these fields, so a value there was put in by hand; one that agrees is harmless.
     */
    fun of(binding: ConsentTransactionStore.Binding?, form: PodConsentForm): ConsentRequest? {
      val posted = ConsentRequest(
        clientId = form.clientId?.trim()?.takeIf { it.isNotBlank() },
        redirectUri = form.redirectUri?.trim()?.takeIf { it.isNotBlank() },
        state = suppliedState(form.state),
        codeChallenge = form.codeChallenge?.trim()?.takeIf { it.isNotBlank() },
        codeChallengeMethod = form.codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
      )
      if (binding == null) return posted
      val bound = with(binding) { ConsentRequest(clientId, redirectUri, state, codeChallenge, codeChallengeMethod) }
      // Field by field, since a field the form leaves out agrees with anything.
      val pairs = listOf(
        posted.clientId to bound.clientId,
        posted.redirectUri to bound.redirectUri,
        posted.state to bound.state,
        posted.codeChallenge to bound.codeChallenge,
        posted.codeChallengeMethod to bound.codeChallengeMethod,
      )
      return bound.takeIf { pairs.all { (sent, kept) -> sent == null || sent == kept } }
    }
  }
}

/**
 * The consent dialog's form as the browser posted it — untrimmed, unvalidated, any of it absent.
 *
 * @param clientId the request fields — this, [redirectUri], [state], [codeChallenge] and
 *   [codeChallengeMethod] — are what a page rendered by an older node carries. A bound screen does
 *   not render them, and one posted anyway must agree with the binding.
 * @param durable whether the durability box was ticked — the adapter decides that, since an
 *   unticked checkbox sends nothing at all.
 * @param action the submit button's own name, or `null` for the ordinary save.
 */
internal data class PodConsentForm(
  val clientId: String?,
  val redirectUri: String?,
  val state: String?,
  val codeChallenge: String?,
  val codeChallengeMethod: String?,
  val csrf: String?,
  val scopes: List<String>?,
  val newContexts: List<String>?,
  val newContextScopes: List<String>?,
  val durable: Boolean,
  val action: String?,
)

/**
 * What a consent submission answers.
 *
 * [Error] carries a [Redirectable] and [Refused] does not — [OAuthErrorDelivery]'s rule as a type,
 * the split [PodAuthorizeResult] makes too.
 */
internal sealed interface PodConsentResult {

  /** A code was minted. [target] is where it goes; the adapter assembles the address. */
  data class Code(val code: String, val target: Redirectable, val state: String?) : PodConsentResult

  /** An OAuth error, delivered the way [OAuthErrorDelivery] says it may be. */
  data class Error(val delivery: OAuthErrorDelivery) : PodConsentResult

  /** The same, and the person's session on this pod ends with it. */
  data class SignedOut(val delivery: OAuthErrorDelivery) : PodConsentResult

  /** A refusal that is not an OAuth error document — see [PodConsentRefusal]. */
  data class Refused(val reason: PodConsentRefusal) : PodConsentResult
}

/**
 * Why a consent submission was refused without an OAuth error document: four by the ordering rule
 * at the top of [PodAuthorizeFlow.authorize], and three because this form cannot be acted on. The
 * form's three are asked first, since the request the other four check is read from its screen.
 *
 * The reason is named and the sentence is not — [PodAuthorizeRefusal] says why, and
 * [MALFORMED_CLIENT_ID] is where the two routes differ.
 */
internal enum class PodConsentRefusal {
  MISSING_REDIRECT_URI,
  UNREGISTERED_CLIENT,
  MALFORMED_CLIENT_ID,
  REDIRECT_URI_NOT_ALLOWED,

  /** No session cookie, or one whose person has signed out since. */
  SESSION_EXPIRED,

  /** The one-time ticket is absent, spent, another session's, or from before a disconnect. */
  FORM_EXPIRED,

  /**
   * The form names another client, redirect, `state` or PKCE challenge than its screen was rendered
   * for. Not redirected: neither client is the one this answer is owed to. The ticket is spent and
   * nothing is written.
   */
  FORM_MISMATCH,
}
