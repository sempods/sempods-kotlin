package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.auth.ConsentTransactionStore
import org.sempods.auth.PersonIdentity
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthErrorDelivery
import org.sempods.auth.core.OAuthErrors
import org.sempods.auth.core.Redirectable
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.HostedPod
import org.sempods.pods.PodFacade
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.ScopePermission
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
) {

  internal fun submit(
    pod: HostedPod,
    form: PodConsentForm,
    session: PodTokenIssuer.SessionPrincipal?,
  ): PodConsentResult {
    val clientState = suppliedState(form.state)

    // Same split as `/authorize`: a consent form submitted after the registration was cleared is
    // not a malformed `client_id`, and telling the person it is sends them looking for a typo.
    val clients = PodClientDirectory.of(pod.id, dynamicClientStore)
    val normalizedClientId = when (val client = clients.identify(form.clientId)) {
      is PodClientIdentity.Known -> client.clientId
      PodClientIdentity.Unregistered -> return PodConsentResult.Refused(PodConsentRefusal.UNREGISTERED_CLIENT)
      PodClientIdentity.Malformed -> return PodConsentResult.Refused(PodConsentRefusal.MALFORMED_CLIENT_ID)
    }

    val normalizedRedirectUri = form.redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodConsentResult.Refused(PodConsentRefusal.MISSING_REDIRECT_URI)

    val redirectTarget = OAuthErrors.redirectTargetFor(clients, normalizedClientId, normalizedRedirectUri)
      ?: return PodConsentResult.Refused(PodConsentRefusal.REDIRECT_URI_NOT_ALLOWED)

    // Two questions, two answers. The session says *who* is submitting; the transaction says
    // *which screen* this is, and that it has not been submitted before. Neither alone is enough:
    // a session-derived token would be the same on every screen the session outlives (so a stale
    // page could be replayed over a narrower consent), and a transaction alone could be lifted out of a
    // page and spent from another browser.
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
    val identity = PersonIdentity(webId = session.webId, alsoKnownAs = session.alsoKnownAs)

    // Ahead of the check below, which refuses a page rendered before this app was disconnected. That
    // check keeps an old page from writing grants back; a sign-out writes none, and refusing it would
    // leave the person signed in with no way out on the page in front of them.
    if (form.action?.trim() == SIGN_OUT_ACTION) {
      podSignOut.signOut(pod.id, pod.name, identity.allUris)
      return PodConsentResult.SignedOut(
        OAuthErrorDelivery.Redirect(redirectTarget, OAuthErrorCode.ACCESS_DENIED, "signed out", clientState),
      )
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
    val standing = consentDecisionStore
      .find(pod.id, normalizedClientId, listOf(identity.webId))
      ?.generation
    // Read once: the disconnect below asks the same question, and two reads could disagree.
    val holdsAnything = holdsAnything(pod, normalizedClientId, identity)
    if (transaction.consentGeneration != standing && !holdsAnything) {
      logger.info {
        "[oauth/consent] rejected: page rendered before the app was disconnected (pod='${pod.name}', " +
            "clientId='$normalizedClientId', rendered=${transaction.consentGeneration ?: "(none)"}, " +
            "standing=${standing ?: "(none)"})"
      }
      return PodConsentResult.Refused(PodConsentRefusal.FORM_EXPIRED)
    }

    // Before anything is created. The form can carry a context the person typed, and choosing to
    // remove an app's access is not the moment to build one for it — they asked for the opposite of
    // an authorization. The empty-submission route to the same place is further down, because it
    // can only be recognised once the selection has been resolved.
    if (form.action?.trim() == DISCONNECT_ACTION) {
      return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)
    }

    val isOwner = podGrantsFacade.isPodOwner(pod, identity.allUris)

    // `public-read` is an additive scope. It can be combined with per-context
    // scopes — no mutual-exclusivity check. Persisted as a grant so
    // prompt=none auto-grant works on subsequent /authorize calls.
    val rawSubmitted = form.scopes
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?.toSet()
      ?: emptySet()
    val publicReadRequested = PUBLIC_READ_SCOPE in rawSubmitted
    val perContextSubmitted = rawSubmitted - PUBLIC_READ_SCOPE
    val newContextsRequested = form.newContexts
      ?.map { ContextPathRules.normalize(it) }
      ?.filter { it.isNotBlank() }
      ?: emptyList()
    // `<relative-path>#<permission>` — a context that does not exist yet has no IRI to name, so
    // the form cannot post one. It used to post `podBaseUrl + path + '#' + perm`, which stopped
    // matching the moment the server started prefixing, and the grants silently vanished.
    val newContextScopesRequested = form.newContextScopes
      ?.map { it.trim() }
      ?.filter { it.isNotBlank() }
      ?: emptyList()

    // Nothing ticked anywhere is the other way to ask for the way out, and it is answerable here:
    // with no scope submitted and no permission on a pending context, no selection can survive the
    // creation below, so creating one would build a context for an authorization that is not
    // happening. The backstop after the resolution stays, for a selection that empties there.
    if (rawSubmitted.isEmpty() && newContextScopesRequested.isEmpty()) {
      return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)
    }

    if (publicReadRequested) {
      // public-read needs at least one public context to be meaningful — drop
      // it from the grant set if the pod has no public contexts (rather than
      // erroring; the user may still want the per-context grants).
      val publicContexts = podFacade.getPublicContexts(podName = pod.name)
      if (publicContexts.isEmpty() && perContextSubmitted.isEmpty() && newContextsRequested.isEmpty()) {
        return failed(
          redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
          "pod has no public-read contexts and no per-context scopes were selected", clientState,
        )
      }
    }

    // Create new contexts submitted from the consent UI (owner only).
    //
    // The path is free user input, so it goes through the same structure rules as the management
    // route ([ContextPathRules]) and gets the same namespace prefix. Until this iteration it did
    // neither: contexts landed directly under the pod root, in the freely writable resource
    // namespace, and no rule the other producer enforced applied here at all.
    //
    // A rejected path is skipped rather than failing the authorization: the user is mid-consent,
    // and losing the whole flow over a mistyped context name would be the worse outcome. The
    // grant set below is computed from what actually exists, so a skipped context simply is not
    // granted.
    //
    // The IRI is built here and nowhere else. The form posts the relative path, both for the
    // context and for its permission checkboxes — a second builder in the template is what made
    // every grant on a newly created context vanish the moment this one started prefixing.
    //
    // TODO: Schnitt 2 — surface a `public` checkbox here so consent-created
    //   contexts can be made anonymously readable; defaults to private for now.
    // TODO: a rejected path is still only a log line. The form validates first, which covers what a
    //   person actually types, but it is a second implementation of these rules and a second
    //   implementation eventually disagrees — a character class already did. The complete answer is
    //   to re-render the consent page with the reason instead of skipping: the owner stays in the
    //   flow and sees it. What that needs is carrying the pending contexts and their ticked
    //   permissions back into the template, so the re-render does not discard the work.
    val createdContexts = mutableMapOf<String, String>()
    if (isOwner) {
      newContextsRequested.forEach { relativePath ->
        fun reject(reason: String) = logger.warn {
          "[oauth/consent] Context rejected: pod='${pod.name}', " +
              "path='${LogSafeText.of(relativePath)}' — $reason"
        }
        ContextPathRules.rejectionReason(relativePath)?.let { return@forEach reject(it) }
        // Same builder as the management route, so the two cannot disagree about what a path maps
        // to — and so a form value carrying `#` or `?` is refused here as well. Concatenating the
        // string instead would have persisted `<pod>/_system/contexts/foo#bar`: unaddressable
        // through `_system/contexts/{path}`, and ambiguous against the `<iri>#<permission>` scope
        // grammar.
        val resolution = ContextPathRules.resolve(pod.baseUrl, relativePath)
        if (resolution is ContextUriResolution.Rejected) {
          return@forEach reject(resolution.reason)
        }
        val contextUri = (resolution as ContextUriResolution.Resolved).uri
        podFacade.createContext(pod = pod, contextUri = contextUri, createdBy = identity.webId)
        createdContexts[relativePath] = contextUri.toString()
        logger.info { "[oauth/consent] Context created: pod='${pod.name}', context='$contextUri'" }
      }
    }

    // The checkboxes of a just-created context, resolved against the IRI it actually got. A scope
    // whose path was rejected above resolves to nothing and is dropped with its context — which is
    // the intended outcome, and the reason this map is keyed by what was created rather than by
    // what was requested.
    val newContextScopesResolved = newContextScopesRequested.mapNotNull { raw ->
      val relativePath = ContextPathRules.normalize(raw.substringBeforeLast('#', missingDelimiterValue = ""))
      val permission = raw.substringAfterLast('#', missingDelimiterValue = "")
      if (ScopePermission.of(permission) == null) {
        return@mapNotNull null
      }
      createdContexts[relativePath]?.let { "$it#$permission" }
    }

    // Re-resolve the user's grants after potential context creation.
    val userGrants = podGrantsFacade.resolveUserGrants(pod, identity.allUris)
    val selectedPerContext = (perContextSubmitted + newContextScopesResolved)
      .filter { userGrants.contains(it) }
      .toSet()

    // Combined grant set: per-context scopes plus the public-read scope if
    // the toggle was ticked (additive model). Public-read is persisted so
    // prompt=none auto-grant honours the choice on later /authorize calls.
    val selectedScopes = if (publicReadRequested) {
      selectedPerContext + PUBLIC_READ_SCOPE
    } else {
      selectedPerContext
    }

    // The backstop for a selection that empties here rather than at the form: every scope the
    // person ticked turned out to be one they no longer hold.
    if (selectedScopes.isEmpty()) {
      return endAuthorization(pod, normalizedClientId, identity, redirectTarget, clientState, holdsAnything)
    }

    // Persist the user's grant selection for this app (replace — the checkbox submission is the
    // authoritative new state, so any previously granted scope the user unchecked must be revoked).
    // The facade re-derives after writing, so an owner-level revocation that landed between
    // `resolveUserGrants` above and this write cannot leave an unbacked grant behind. What comes
    // back is what actually survived.
    val persistedScopes = podGrantsFacade.replaceAppGrants(
      pod = pod,
      appId = normalizedClientId,
      webId = identity.webId,
      subjectUris = identity.allUris,
      grants = selectedScopes,
      grantedBy = identity.webId,
    )

    if (persistedScopes.isEmpty()) {
      // Recoverable: the person's authority changed while they were deciding. `consent_required`
      // rather than `access_denied` — nobody refused anything, the basis simply moved.
      logger.warn {
        "[oauth/consent] Selection void — owner-level access changed during consent: " +
            "pod='${pod.name}', clientId='$normalizedClientId', webId='${identity.webId}'"
      }
      return failed(
        redirectTarget, OAuthErrorCode.CONSENT_REQUIRED,
        "granted access changed while consenting; please re-authorize", clientState,
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
          "webId='${identity.webId}', scopes=${persistedScopes.size}, public_read=$publicReadRequested, " +
          "durable=${form.durable}, generation=${decision.generation}"
    }

    // Slim access token: context permissions are resolved server-side from the grant just
    // persisted, so only feature scopes (e.g. `public-read`) travel in the token.
    val tokenFeatureScopes = if (publicReadRequested) setOf(PUBLIC_READ_SCOPE) else emptySet()

    return codes.issue(
      pod = pod,
      clientId = normalizedClientId,
      webId = identity.webId,
      scopes = tokenFeatureScopes,
      target = redirectTarget,
      state = clientState,
      codeChallenge = form.codeChallenge?.trim()?.takeIf { it.isNotBlank() },
      codeChallengeMethod = form.codeChallengeMethod?.trim()?.takeIf { it.isNotBlank() },
      via = PodCodeIssuance.CONSENT,
      consentGeneration = decision.generation,
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
   * Whether this app holds anything for this person — the question that decides both whether the
   * way out is offered and whether taking it means anything. Asked over every URI that names the
   * person: an authorization stored under an alias is one they can still end.
   */
  private fun holdsAnything(pod: HostedPod, clientId: String, identity: PersonIdentity): Boolean =
    podGrantsFacade.appGrants(pod.id, clientId, identity.allUris).isNotEmpty()

  /**
   * End what this app holds for this person.
   *
   * The grants go, the decision is written as a refusal — a silence would read as an authorization
   * that predates the control and be left alone — and the refresh families are revoked, because
   * withholding that is merely declining to extend would leave the person's most emphatic gesture
   * with nothing to show for it. The client is still told `access_denied`: the request really was
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
      podGrantsFacade.replaceAppGrants(
        pod = pod,
        appId = clientId,
        webId = uri,
        subjectUris = identity.allUris,
        grants = emptySet(),
        grantedBy = identity.webId,
      )
    }
    val decision = recordDecision(pod, clientId, identity, durable = false)
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
  }
}

/**
 * The consent dialog's form as the browser posted it — untrimmed, unvalidated, any of it absent.
 *
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
 * Why a consent submission was refused without an OAuth error document: three by the ordering rule
 * at the top of [PodAuthorizeFlow.authorize], and three because this form cannot be acted on.
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
}
