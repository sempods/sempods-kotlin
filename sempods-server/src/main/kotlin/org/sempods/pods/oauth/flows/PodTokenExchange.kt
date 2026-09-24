package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.commons.logging.LogSafeText
import org.sempods.pods.oauth.PodTokenIssuer
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import org.sempods.auth.core.AuthorizationCodeStore
import org.sempods.auth.core.ClientId
import org.sempods.auth.core.OAuthErrorCode
import org.sempods.auth.core.OAuthSyntax
import org.sempods.auth.core.Pkce
import org.sempods.auth.core.RefreshTokenStore
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.PodId
import org.sempods.pods.grants.SERVICE_CLIENTS_MANAGE_SCOPE
import org.sempods.pods.grants.OFFLINE_ACCESS_SCOPE
import org.sempods.pods.grants.PUBLIC_READ_SCOPE
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.grants.PodScopeValidator
import org.sempods.pods.grants.SERVICE_CLIENTS_INSTALL_SCOPE
import org.sempods.pods.oauth.PodConsentDecisionStore
import org.sempods.pods.oauth.PodInstallationAuthorityStore
import org.sempods.pods.oauth.PodManagementAuthorityStore
import org.sempods.pods.oauth.PrivilegedAuthorityRows
import org.sempods.pods.oauth.PodRefreshToken
import org.sempods.pods.oauth.PodRefreshTokenStore
import java.time.Duration
import java.time.Instant

/**
 * The three exchanges the token endpoint performs: redeeming an authorization code, rotating the
 * refresh token it seeded, and authenticating a service with its own credentials.
 *
 * **Every decision here is the pod's, and none of them is HTTP.** What a code is worth, which
 * scopes survive, how long a family lives, when a rotation is reuse and when a consent has moved
 * under an exchange already in flight — this class answers all of it and hands back a
 * [PodTokenResult] that says nothing about status codes, headers or JSON. The endpoint binds the
 * request and renders the answer.
 *
 * **The order of the checks is the contract, not a style.** Each of the two person exchanges signs
 * its access token before its last look at the consent decision, reads the decision again after
 * seeding its family, and sweeps what it supersedes only once the successor exists.
 * `PodSignOut.signOut` writes in the order that makes those reads sufficient (`SPS-AUTH-062`,
 * `SPS-AUTH-063`), so moving one of them re-opens a window on the other side. The comments at each
 * step say which. [exchangeServiceClient] authorizes no person and seeds no family, so it runs
 * none of that. [installationToken] signs and re-reads the same way and seeds no family either, so
 * it has nothing to sweep and nothing to take back on a refusal.
 */
class PodTokenExchange @Inject internal constructor(
  private val authorizationCodeStore: AuthorizationCodeStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val consentDecisionStore: PodConsentDecisionStore,
  private val podGrantsFacade: PodGrantsFacade,
  private val dynamicClientStore: DynamicClientStore,
  private val podTokenIssuer: PodTokenIssuer,
  private val serviceClients: PodServiceClientStore,
  private val installationAuthorities: PodInstallationAuthorityStore,
  private val managementAuthorities: PodManagementAuthorityStore,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  internal fun redeemCode(
    pod: PodId,
    podName: String,
    code: String?,
    redirectUri: String?,
    clientId: String?,
    codeVerifier: String?,
  ): PodTokenResult {
    val normalizedCode = code?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing code")
    val normalizedRedirectUri = redirectUri?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing redirect_uri")
    // Held to the same rule `readClientId` holds one to: the token endpoint takes `client_id` as
    // an unauthenticated form parameter and never goes through that method.
    val normalizedClientId = clientId?.trim()?.takeIf(ClientId::isValid)
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing or malformed client_id")

    // Consume the authorization code (one-time use).
    val entry = authorizationCodeStore.consume(normalizedCode)
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "invalid or expired authorization code")

    // Validate that redirect_uri and client_id match the original authorize request.
    if (entry.redirectUri != normalizedRedirectUri) {
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "redirect_uri mismatch")
    }
    if (entry.clientId != normalizedClientId) {
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "client_id mismatch")
    }
    if (entry.realm != podName) {
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "pod mismatch")
    }

    // PKCE verification. The challenge is bound to a local because the store lives in another
    // module now, where Kotlin will not smart-cast a public property across the boundary.
    val issuedChallenge = entry.codeChallenge
    if (issuedChallenge != null) {
      // Not trimmed, unlike every other form value here. RFC 7636 §4.1's alphabet has no
      // whitespace, so trimming would grant a leniency the rule does not — and grant it at two of
      // this repository's three token endpoints, since the third passes the value as sent.
      val verifier = codeVerifier?.takeIf { it.isNotBlank() }
        ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing code_verifier")
      // `Pkce` rather than a local comparison: it compares in constant time. A byte-by-byte
      // early exit leaks the stored challenge one character per request, and the challenge is
      // what stands between an intercepted authorization code and a token. The method is
      // re-checked because a stored row is the only thing that says which one was agreed —
      // `/authorize` refuses anything else, so this is the integrity check, not the gate.
      if (!Pkce.isSupportedMethod(entry.codeChallengeMethod) ||
        !Pkce.verifyS256(verifier, issuedChallenge)
      ) {
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "PKCE verification failed")
      }
    }

    // Anonymous public-read remains a short-lived special token because there
    // is no user grant row to bind refresh-token rotation against. Authenticated
    // public-read, including public-read-only consent, is a normal additive
    // scope and continues through the standard refresh-token path below.
    if (entry.scopes == setOf(PUBLIC_READ_SCOPE) && entry.subject.startsWith("urn:sempods:anon:")) {
      logger.info {
        "[oauth/token] public-read token issued: pod='${podName}', " +
            "clientId='${entry.clientId}', webId='${entry.subject}'"
      }
      dynamicClientStore.touchLastAuthorized(pod, entry.clientId)
      return publicReadToken(podName = podName, clientId = entry.clientId, webId = entry.subject)
    }

    // A code is a request, not an authority: it must not pick up a consent given after it, so the
    // generation it carries is compared against the one standing now.
    //
    // **A code carrying none is refused outright.** Every code minted for a person comes from an
    // authorization that has been answered — consent records an answer, and auto-grant reaches its
    // code only where one is on record — so a code without a generation is the debris of a
    // half-written consent or older than the control itself. The anonymous `public-read` exchange
    // has no person and no answer, and returned above.
    val decision = consentDecisionStore.find(pod, entry.clientId, listOf(entry.subject))
    val issuedUnder = entry.consentGeneration
    if (decision == null || issuedUnder == null || decision.generation != issuedUnder) {
      logger.info {
        "[oauth/token] authorization code superseded by a later consent: pod='${podName}', " +
            "clientId='${entry.clientId}', webId='${entry.subject}', " +
            "codeGeneration=$issuedUnder, current=${decision?.generation ?: "(none)"}"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "authorization code superseded by a later consent")
    }

    // Hard guarantee the access token is slim: keep only feature scopes, whatever the
    // authorization-code entry happens to carry. Context permissions are resolved per request
    // from the grant store, never echoed into the token. This also bounds the refresh row.
    val featureScopes = entry.scopes.intersect(PodScopeValidator.featureScopes)

    // ── An installation authority takes its own exit ──────────────────────
    // It is the one person-exchange that seeds no family, so it leaves before the family is
    // measured rather than after. A code that carries a privileged scope beside anything else
    // never came from a dialog — every screen that offers one offers nothing else — and a mixed
    // set is refused rather than narrowed, because narrowing is how a one-shot authority would
    // quietly acquire a renewable neighbour.
    val privileged = featureScopes.intersect(PodScopeValidator.privilegedFeatureScopes)
    if (privileged.isNotEmpty()) {
      if (featureScopes != privileged || privileged.size != 1) {
        logger.warn {
          "[oauth/token] mixed scope set on an installation code: pod='$podName', " +
              "clientId='${entry.clientId}', scopes=${featureScopes.sorted().joinToString(" ")}"
        }
        return PodTokenResult.Refused(
          OAuthErrorCode.INVALID_GRANT,
          "an installation authority cannot be combined with another scope",
        )
      }
      return installationToken(
        pod = pod,
        podName = podName,
        entry = entry,
        scopes = privileged,
        issuedUnder = issuedUnder,
      )
    }

    // Read from the stored consent, not from the code: a code carries what was asked for, never
    // the authority. What the answer settles is how long the family lives, not whether there is
    // one — an app the person keeps in front of them needs a way back that does not run through a
    // third-party cookie.
    // **An authorization nobody has answered mints nothing.** Before an installation could write a
    // generation into this document without answering it, `decision == null` above caught this
    // case; a row with no lifetime answer is the same state wearing a generation, and the same
    // refusal. Picking a lifetime for it here would let the installation screen settle a question
    // it never put to the person.
    val durable = decision.durable
      ?: return PodTokenResult.Refused(
        OAuthErrorCode.INVALID_GRANT,
        "this authorization has not been answered; re-authorize",
      )
    val lifetime =
      if (durable) PodRefreshTokenStore.Lifetime.DURABLE
      else PodRefreshTokenStore.Lifetime.SESSION

    // What this exchange supersedes, named *before* the successor exists — see
    // `PodRefreshTokenStore.liveFamilies` for why the order is the whole argument. Across the
    // person's derivable URIs, because the superseded family may have been minted under the twin
    // of the URI this code carries.
    //
    // Measured for both answers, because both mint one. An auto-granted reconnect records no new
    // decision and so revokes nothing: gated on the durable answer, every visit would leave one
    // more live family behind, each renewing a window of its own.
    val superseded = refreshTokenStore.liveFamilies(
      pod = pod,
      clientId = entry.clientId,
      webIds = webIdUriDeriver.derivableAliases(entry.subject),
    )

    val issuedRefresh = refreshTokenStore.issueNewFamily(
      pod = pod,
      podName = podName,
      clientId = entry.clientId,
      webId = entry.subject,
      scopes = featureScopes,
      lifetime = lifetime,
    )

    // Signed before the check below and sent only if it passes. A sign-out moves the generation
    // before it writes its last instant, so a token that passes was signed before that instant and
    // is refused wherever it is presented. Signed after the check, it could be dated after the
    // instant and live its hour (`PodSignOut.signOut`).
    val accessToken = signAccessToken(
      podName = podName,
      clientId = entry.clientId,
      webId = entry.subject,
      scopes = featureScopes,
      familyEndsAt = issuedRefresh.token.endsAt,
    )

    // The decision is read once more, after the insert, and it is the only gate this path needs.
    // Every write to it raises the generation, so a withdrawal landing mid-exchange has already
    // moved what this code carries — asking about `durable` separately beforehand could not fire on
    // anything the comparison misses. The message still tells the two apart, because a person who
    // withheld the durable connection is owed a different sentence than one whose consent moved.
    //
    // **Every exchange passes here, whatever lifetime it carries.** A consent change cannot recall an
    // access token, so the only moment to refuse one is before it goes out (`SPS-AUTH-062`,
    // `SPS-AUTH-063`) — and a bearer with a fresh `jti` and `iat` satisfies
    // `ReauthorizeChallengeStore`, so a client that got past this reads "already authorized" and
    // never meets the forced consent screen.
    //
    // Two gaps remain and neither grants authority the client did not hold: this read and the mint
    // are two moments, and the raise is one `updateMany` over the person's alias documents, atomic
    // per document. Closing them needs a generation spanning a person rather than a URI, bound to
    // issuance rather than compared before it; `recordDecision` keeps one answer per URI so a code
    // issued under an alias can go stale on its own. No test reaches either — the check before the
    // exchange answers anything a test can set up.
    val standing = consentDecisionStore.find(pod, entry.clientId, listOf(entry.subject))
    if (standing?.generation != issuedUnder) {
      val revoked = refreshTokenStore.revokeFamily(issuedRefresh.token.familyId)
      val withdrawn = standing?.durable == false
      logger.info {
        "[oauth/token] consent moved mid-exchange — nothing issued for this code: " +
            "pod='${podName}', clientId='${entry.clientId}', webId='${entry.subject}', " +
            "codeGeneration=$issuedUnder, durableWithheld=$withdrawn, revokedRows=$revoked"
      }
      return PodTokenResult.Refused(
        OAuthErrorCode.INVALID_GRANT,
        if (withdrawn) "the durable connection was withdrawn" else "authorization code superseded by a later consent",
      )
    }

    // A reconnect replaces the connection it supersedes rather than adding to it — the same answer
    // the withholding path gives from the other end, so that reconnecting twice does not leave two
    // families behind, each renewing a window of its own. Swept only once the successor exists, so
    // neither answer ever leaves the person holding nothing.
    if (superseded.isNotEmpty()) {
      val retired = refreshTokenStore.revokeFamilies(superseded)
      if (retired > 0) {
        logger.info {
          "[oauth/token] reconnect retired what it supersedes: pod='${podName}', " +
              "clientId='${entry.clientId}', webId='${entry.subject}', " +
              "retiredFamilies=${superseded.size}, retiredRows=$retired"
        }
      }
    }

    logger.info {
      "[oauth/token] Tokens issued (authorization_code): pod='${podName}', clientId='${entry.clientId}', " +
          "webId='${entry.subject}', scopes=${featureScopes.size}, lifetime=${lifetime.kind}, " +
          "familyId='${issuedRefresh.token.familyId}'"
    }

    // Liveness touch on the DCR row. Every completed flow reaches one of the four call sites —
    // this one, the anonymous public-read branch above, the installation below and the rotation
    // after it — so a connection stays as live under the short lifetime as under the long one, and
    // the shorter window is not mistaken for an abandoned app. Best-effort: did:web clients have no
    // DCR row and return false here, which is fine.
    dynamicClientStore.touchLastAuthorized(pod, entry.clientId)

    return issued(
      accessToken = accessToken,
      scopes = featureScopes,
      refreshToken = issuedRefresh.plaintext,
    )
  }

  /**
   * The one-shot authority an installation code is worth.
   *
   * **No family.** A refresh token would make the authority renewable, which is the one thing it
   * must not be; `offline_access` in the request preselected a control the installation dialog
   * does not render, and there was never an answer for it to carry. `docs/auth/oauth.md`
   * §"offline_access" lists this beside the two other exchanges that seed none.
   *
   * **The token is inert on its own.** It carries no context permission — `GrantStorePodAuthorizer`
   * resolves none for a bearer holding a privileged feature scope — and what it does carry is
   * spent the first time it is used, by the row this writes.
   *
   * @param issuedUnder the consent generation the code was minted under, compared once more here.
   */
  private fun installationToken(
    pod: PodId,
    podName: String,
    entry: AuthorizationCodeStore.Entry,
    scopes: Set<String>,
    issuedUnder: Long,
  ): PodTokenResult {
    // Signed before the check below, the rule the two family exchanges follow and for the same
    // reason: a sign-out moves the generation before it writes its last instant, so a token that
    // passes was signed before that instant. `familyEndsAt` is null because there is no family —
    // the hour is the whole of this bearer's life.
    val accessToken = checkNotNull(
      signAccessToken(
        podName = podName,
        clientId = entry.clientId,
        webId = entry.subject,
        scopes = scopes,
        familyEndsAt = null,
      ),
    ) { "an access token with no family deadline always has a lifetime" }

    val standing = consentDecisionStore.find(pod, entry.clientId, listOf(entry.subject))
    if (standing?.generation != issuedUnder) {
      logger.info {
        "[oauth/token] installation code superseded by a later consent: pod='$podName', " +
            "clientId='${entry.clientId}', webId='${entry.subject}', " +
            "codeGeneration=$issuedUnder, current=${standing?.generation ?: "(none)"}"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "authorization code superseded by a later consent")
    }

    // Written after that check, which inverts the order the family exchanges use. They seed first
    // and revoke on a refusal, because a family is a thing to take back; this row is what makes
    // the bearer worth anything, so a run dying ahead of it leaves an inert token and the owner
    // runs the installer again. Written first, it would leave a live authority behind a token
    // this exchange then refused to hand out.
    val authorities: PrivilegedAuthorityRows = when (val scope = scopes.single()) {
      SERVICE_CLIENTS_INSTALL_SCOPE -> installationAuthorities
      SERVICE_CLIENTS_MANAGE_SCOPE -> managementAuthorities
      else -> error("no authority store for privileged scope '$scope'")
    }
    authorities.record(
      pod = pod,
      jti = accessToken.jti,
      clientId = entry.clientId,
      webId = entry.subject,
      disconnects = standing?.disconnects ?: 0L,
      subjectUris = entry.subjectUris,
    )

    logger.info {
      "[oauth/token] Privileged authority issued: pod='$podName', clientId='${entry.clientId}', " +
          "webId='${entry.subject}', scopes='${scopes.sorted().joinToString(" ")}'"
    }

    dynamicClientStore.touchLastAuthorized(pod, entry.clientId)

    return issued(accessToken = accessToken, scopes = scopes, refreshToken = null)
  }

  /**
   * A service authenticating as itself (RFC 6749 §4.4), with no person behind it.
   *
   * The credentials arrive already parsed: HTTP Basic is the adapter's format, and what this
   * decides is whether they name a registration and what the token it mints may say.
   *
   * @param requestedScope rejected outright when present, rather than narrowed — see below.
   */
  internal fun exchangeServiceClient(
    pod: PodId,
    podName: String,
    clientId: String,
    secret: String,
    requestedScope: String?,
  ): PodTokenResult {
    val client = serviceClients.authenticate(pod, clientId, secret)
    if (client == null) {
      logger.info {
        "[oauth/token] client_credentials auth failed: pod='$podName', " +
            "clientId='${LogSafeText.of(clientId)}'"
      }
      return PodTokenResult.ClientAuthenticationRequired("unknown client_id or invalid secret")
    }

    // Down-scoping is NOT supported on client_credentials. A slim service token carries no
    // per-token state to express a subset, and the resolver always grants the client's full
    // registered set from `PodServiceClientDao` at request time. Accepting `scope=` and
    // silently granting more than requested would be a confused-deputy footgun, so reject it
    // outright until per-token service down-scope state exists.
    // TODO: support per-token service down-scoping (carry the requested subset as a signed,
    //   resolver-honored claim) — then this rejection can relax to the subset path.
    if (!requestedScope.isNullOrBlank()) {
      return PodTokenResult.Refused(
        OAuthErrorCode.INVALID_SCOPE,
        "scope down-scoping is not supported on client_credentials; the token grants the " +
            "client's full registered scope set",
      )
    }

    if (client.scopes.isEmpty()) {
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_SCOPE, "no scopes registered for this client")
    }

    // Only feature scopes travel in a slim service token. Service clients register context
    // scopes only (feature scopes are rejected at registration), so this is empty today.
    val tokenFeatureScopes = client.scopes.intersect(PodScopeValidator.featureScopes)

    val accessToken = podTokenIssuer.issueServiceToken(
      pod = podName,
      clientId = client.clientId,
      scopes = tokenFeatureScopes,
    )
    serviceClients.touchLastUsed(pod, client.clientId)

    logger.info {
      "[oauth/token] Service token issued (client_credentials): pod='$podName', " +
          "clientId='${client.clientId}', registeredScopes=${client.scopes.size}, " +
          "tokenFeatureScopes=${tokenFeatureScopes.size}, label='${client.label ?: "(unset)"}'"
    }

    return PodTokenResult.Issued(
      accessToken = accessToken,
      expiresInSeconds = PodTokenIssuer.SERVICE_TOKEN_TTL_SECONDS,
      scopes = tokenFeatureScopes,
      statesEmptyScope = true,
    )
  }

  internal fun refresh(
    pod: PodId,
    podName: String,
    refreshToken: String?,
    clientId: String?,
    requestedScope: String?,
  ): PodTokenResult {
    val normalizedToken = refreshToken?.trim()?.takeIf { it.isNotBlank() }
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing refresh_token")
    // Same rule as the authorization-code branch above, and load-bearing here: the refusals below
    // name this value before anything has matched it against a stored one.
    val normalizedClientId = clientId?.trim()?.takeIf(ClientId::isValid)
      ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_REQUEST, "missing or malformed client_id")

    val lookup = refreshTokenStore.lookup(normalizedToken)
    val token = lookup.token
    when (lookup.state) {
      // TODO: the line names the pod, the client and the token — and still cannot say *who* tried.
      //  On a miss the store returns no token, so there is no family id and no WebID, and the
      //  submitted `client_id` names an app rather than an installation. Attributing a failed
      //  attempt to a person would mean keeping durable tombstones for tokens that no longer
      //  exist — a retention design that has to answer what is worth keeping about a credential
      //  that failed, not a log line. What the store can say is in
      //  `docs/auth/oauth.md` §"Refresh token rotation".
      RefreshTokenStore.LookupState.NOT_FOUND -> {
        // "unknown or expired", because the two are the same row-absence here: an expired token
        // reports EXPIRED only until the TTL index reaps it, and NOT_FOUND ever after. Reading
        // this line as "forged" would be wrong for the commoner of the two cases.
        logger.warn {
          "[oauth/token] refresh_token not recognized — unknown, or expired and already reaped: " +
              "pod='${podName}', clientId='$normalizedClientId', tokenFp='${lookup.fingerprint}'"
        }
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token not recognized")
      }

      RefreshTokenStore.LookupState.EXPIRED -> {
        logger.info {
          "[oauth/token] refresh_token expired: pod='${podName}', clientId='$normalizedClientId', " +
              "familyId='${token!!.familyId}'"
        }
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token expired")
      }

      RefreshTokenStore.LookupState.REVOKED -> {
        logger.warn {
          "[oauth/token] refresh_token revoked: pod='${podName}', clientId='$normalizedClientId', " +
              "familyId='${token!!.familyId}'"
        }
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token revoked")
      }

      RefreshTokenStore.LookupState.REUSED -> {
        // OAuth 2.1 reuse-detection: this token was already exchanged once. A correctly
        // behaving client keeps only the successor — whatever presented it again is stale
        // state or a thief. Kill the whole family to pull the plug on any in-flight child
        // token the attacker might already be holding.
        val revoked = refreshTokenStore.revokeFamily(token!!.familyId)
        logger.warn {
          "[oauth/token] refresh_token reuse detected — revoking family: pod='${podName}', " +
              "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
        }
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected")
      }

      RefreshTokenStore.LookupState.ACTIVE -> Unit
    }
    token!!

    // Bind the token to its original pod + client. Prevents a client from taking a refresh
    // token issued for another pod/client and presenting it here.
    if (token.owner.pod != pod) {
      logger.warn {
        "[oauth/token] refresh_token pod mismatch: tokenPod='${token.owner.podName}', requestPod='${podName}', " +
            "clientId='$normalizedClientId'"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token does not belong to this pod")
    }
    if (token.owner.clientId != normalizedClientId) {
      logger.warn {
        "[oauth/token] refresh_token client mismatch: tokenClient='${token.owner.clientId}', " +
            "requestClient='$normalizedClientId', pod='${podName}'"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token does not belong to this client")
    }

    // Context permissions are resolved per request from the durable grant store, so a partial
    // revocation needs no token change. The session is dead only when the user has revoked
    // EVERY grant for this app (context grants and feature scopes both live in the store) —
    // then force re-consent and revoke the family so a stray rotation cannot re-hydrate it.
    val currentGrants = podGrantsFacade.appGrants(pod, token.owner.clientId, listOf(token.owner.webId))
    if (currentGrants.isEmpty()) {
      refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] refresh_token grants revoked since issue — forcing re-consent: " +
            "pod='${podName}', clientId='$normalizedClientId', webId='${token.owner.webId}', " +
            "familyId='${token.familyId}'"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "all previously granted scopes have been revoked")
    }

    // The slim token carries only feature scopes; keep the ones the user still grants
    // (e.g. drop public-read if it was revoked). The final `.intersect(featureScopes)` is a
    // hard guarantee against context scopes leaking from a legacy/seeded refresh row —
    // context permissions are resolved per request, never echoed into the token.
    //
    // Minus the privileged ones. An installation authority seeds no family, so no row written by
    // this server carries one; what this answers for is a row that predates the rule. It is the
    // second half of the same promise the code exchange makes — a one-shot authority does not
    // become renewable, by a lifetime tick or by a scope set that happens to survive a rotation.
    val effectiveFeatureScopes = (token.scopes intersect currentGrants)
      .intersect(PodScopeValidator.featureScopes)
      .minus(PodScopeValidator.privilegedFeatureScopes)

    // Optional down-scoping of the feature scopes. Unknown scopes are rejected per RFC 6749
    // §6 ("The requested scope […] MUST NOT include any scope not originally granted").
    //
    // `offline_access` is taken out of that comparison first. Clients hold scope lists carrying it
    // and send them back, which is the standard thing to do with the `scope` of a token response, so
    // refusing the echo would break exactly the clients that behaved correctly. It is never a
    // feature scope, so it cannot be down-scoped *to*; what it names is the connection this request
    // is already proving it holds, and a refusal on record has ended the family further up.
    val requested = OAuthSyntax.parseScope(requestedScope)
    val finalScopes = if (requestedScope.isNullOrBlank()) {
      effectiveFeatureScopes
    } else {
      val requestedFeatures = requested - OFFLINE_ACCESS_SCOPE
      val unknown = requestedFeatures - effectiveFeatureScopes
      if (unknown.isNotEmpty()) {
        return PodTokenResult.Refused(OAuthErrorCode.INVALID_SCOPE, "requested scopes not covered by this refresh token")
      }
      requestedFeatures
    }

    // A refusal ends the family, whether or not the withdrawal's own revocation reached it: that
    // sweep sees the rows that exist at the moment it runs, and rotation inserts one after it.
    if (endsOnRefusal(pod, token)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] refresh refused — the durable connection was withdrawn: pod='${podName}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "the durable connection was withdrawn")
    }

    // Rotate atomically. If another caller slipped in between our lookup and rotation,
    // markRotated() returns false — that's an observed reuse event (could be a race too,
    // but treating it as reuse is the safe default per OAuth 2.1).
    if (!refreshTokenStore.markRotated(token.tokenHash)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.warn {
        "[oauth/token] refresh_token rotation race — treating as reuse: pod='${podName}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token reuse detected")
    }

    val issuedRefresh = refreshTokenStore.issueInFamily(previous = token, scopes = finalScopes)

    // Signed before the checks below, for the reason the code exchange gives: the sign-out's sweep
    // lands before its last instant, so a token signed ahead of a check that passes is dated before it.
    val accessToken = signAccessToken(
      podName = podName,
      clientId = token.owner.clientId,
      webId = token.owner.webId,
      scopes = finalScopes,
      // The successor's deadline, not the predecessor's. A family that predates the terms acquires
      // one in this very rotation (`RefreshTokenStore.issueInFamily`), so the row that was read
      // still carries none — capping against that would hand out a full hour past a deadline that
      // came into existence one statement ago.
      familyEndsAt = issuedRefresh.token.endsAt,
    )

    // A retirement landing between the rotation and that insert revoked the rows it found, and this
    // successor appeared after it — alive, in the family a reconnect had just replaced. `markRotated`
    // answers for a retirement arriving earlier, since it refuses a revoked row; this answers for
    // one arriving in between, and the two together leave it nowhere to land unseen. The window is
    // between two statements and has no test; `a family the retirement swept cannot be refreshed
    // back to life` covers the ordinary path and says so.
    if (refreshTokenStore.noLongerStands(token.tokenHash)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] family retired mid-rotation — successor revoked: pod='${podName}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "refresh token revoked")
    }

    // The third of the three, and the one the grant cascade needs. `currentGrants` was read before
    // any of this, and a context deletion writes in between: it removes the app's last grant, names
    // this family's live row, then finds it rotated and leaves it alone — deliberately, so that
    // replaying the spent row still ends the family. What that leaves behind is a successor for an
    // app holding nothing, and this is the last moment it can be answered for. Untestable for the
    // same reason as its two neighbours; the check before the insert covers the ordinary case.
    if (podGrantsFacade.appGrants(pod, token.owner.clientId, listOf(token.owner.webId)).isEmpty()) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] grants revoked mid-rotation — successor revoked: pod='${podName}', " +
            "clientId='$normalizedClientId', familyId='${token.familyId}', revokedRows=$revoked"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "all previously granted scopes have been revoked")
    }

    // Asked again, because the check above and this insert are two moments: a withdrawal landing
    // between them revokes what it can see and misses the row about to appear. Whoever arrives
    // second undoes the other's work rather than leaving a live successor behind.
    if (endsOnRefusal(pod, token)) {
      val revoked = refreshTokenStore.revokeFamily(token.familyId)
      logger.info {
        "[oauth/token] durable connection withdrawn mid-rotation — successor revoked: " +
            "pod='${podName}', clientId='$normalizedClientId', familyId='${token.familyId}', " +
            "revokedRows=$revoked"
      }
      return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "the durable connection was withdrawn")
    }

    logger.info {
      "[oauth/token] Tokens issued (refresh_token): pod='${podName}', clientId='${token.owner.clientId}', " +
          "webId='${token.owner.webId}', scopes=${finalScopes.size}, familyId='${token.familyId}'"
    }

    dynamicClientStore.touchLastAuthorized(pod, token.owner.clientId)

    return issued(
      accessToken = accessToken,
      scopes = finalScopes,
      refreshToken = issuedRefresh.plaintext,
    )
  }

  /**
   * The anonymous `scope=public-read` answer: [issued] without the refresh token.
   *
   * Public-read is unprivileged, so a long-lived family with reuse detection adds persistence cost
   * and no security. A client re-authorizes when the token expires.
   */
  private fun publicReadToken(podName: String, clientId: String, webId: String): PodTokenResult.Issued =
    PodTokenResult.Issued(
      accessToken = podTokenIssuer.issue(
        pod = podName,
        webId = webId,
        clientId = clientId,
        scopes = setOf(PUBLIC_READ_SCOPE),
      ),
      expiresInSeconds = PodTokenIssuer.USER_TOKEN_TTL_SECONDS,
      scopes = setOf(PUBLIC_READ_SCOPE),
    )

  /**
   * How long an access token issued beside a family whose deadline is [familyEndsAt] may live: an
   * hour, or the rest of the family where that is less. `null` says the family is over.
   *
   * One number, spent twice — on `expires_in` and on the JWT's `exp`. Derived separately they drift,
   * and a client trusting the wrong one is what "seven days" turning into seven days and an hour
   * looks like.
   */
  private fun accessTokenTtl(familyEndsAt: Instant?): Long? {
    if (familyEndsAt == null) return PodTokenIssuer.USER_TOKEN_TTL_SECONDS
    val remaining = Duration.between(Instant.now(), familyEndsAt).seconds
    return if (remaining <= 0) null else minOf(PodTokenIssuer.USER_TOKEN_TTL_SECONDS, remaining)
  }

  /**
   * A signed access token, the lifetime it was signed with (which `expires_in` repeats) and the
   * `jti` it carries — the name [installationToken] records an installation authority under.
   */
  private class SignedAccessToken(val token: String, val ttlSeconds: Long, val jti: String)

  /**
   * Signs the access token an exchange hands out, or answers null where its family is already over.
   *
   * Apart from [issued] so that an exchange can sign before its last check and answer after it.
   * The refusal of a null stays with the answer, so the checks in between keep their say.
   */
  private fun signAccessToken(
    podName: String,
    clientId: String,
    webId: String,
    scopes: Set<String>,
    familyEndsAt: Instant?,
  ): SignedAccessToken? {
    val ttlSeconds = accessTokenTtl(familyEndsAt) ?: return null
    val issued = podTokenIssuer.issueWithId(
      pod = podName,
      webId = webId,
      clientId = clientId,
      scopes = scopes,
      ttlSeconds = ttlSeconds,
    )
    return SignedAccessToken(issued.token, ttlSeconds, issued.jti)
  }

  private fun issued(
    accessToken: SignedAccessToken?,
    scopes: Set<String>,
    refreshToken: String?,
  ): PodTokenResult {
    // Refused here only in the millisecond the deadline itself falls on: `RefreshTokenStore.lookup`
    // compares with `isBefore`, so a row is still ACTIVE exactly at its expiry, and the clamp holds
    // every expiry at or below the family's deadline. One millisecond later the refresh is already
    // answered `EXPIRED`. The branch stays because it is the structural half of the guarantee — no
    // bearer leaves this server outliving its family, whatever wrote the row.
    accessToken ?: return PodTokenResult.Refused(OAuthErrorCode.INVALID_GRANT, "the connection has ended")
    return PodTokenResult.Issued(
      accessToken = accessToken.token,
      expiresInSeconds = accessToken.ttlSeconds,
      scopes = scopes,
      refreshToken = refreshToken,
    )
  }

  /**
   * Whether a refusal on record ends **this** family.
   *
   * A session family exists *because* the answer was "no", so asking only whether the person
   * refused would end every one of them at its first rotation — the feature would do nothing, and
   * a tester who ticks the box would never see it. What a refusal ends is a family minted on the
   * long terms, or one grandfathered onto them ([PodRefreshTokenStore.lifetimeOf]).
   *
   * A session family keeps its withdrawal safety net elsewhere: the consent submission sweeps every
   * family this app holds for this person, rotated rows included, so its predecessor is no longer
   * standing and [PodRefreshTokenStore.noLongerStands] revokes the successor.
   *
   * Not the negation of granted, either: an authorization with nothing recorded predates the
   * control and is left alone, which is why this asks for a recorded refusal rather than for the
   * absence of a grant.
   */
  private fun endsOnRefusal(pod: PodId, token: PodRefreshToken): Boolean =
    refreshTokenStore.lifetimeOf(token) == PodRefreshTokenStore.Lifetime.DURABLE &&
        consentDecisionStore.find(pod, token.owner.clientId, listOf(token.owner.webId))?.durable == false

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}

/**
 * What a token exchange answers.
 *
 * [Refused.description] is kept rather than left to the adapter because it is the protocol's own
 * `error_description` and not presentation: a client switches on the code and shows the sentence,
 * and every one of them is a value this server wrote.
 */
internal sealed interface PodTokenResult {

  /**
   * @param scopes the feature scopes the bearer carries — context permissions are resolved per
   *   request from the grant store and never travel in a token.
   * @param refreshToken null where the exchange hands none back, which is the anonymous
   *   public-read case.
   */
  data class Issued(
    val accessToken: String,
    val expiresInSeconds: Long,
    val scopes: Set<String>,
    val refreshToken: String? = null,
    /**
     * Whether `scope` is named even when [scopes] is empty — true for a service token, which
     * states its registered set either way. `PodTokenResponses.tokens` argues why the three
     * success shapes differ here and why making them agree would narrow this one.
     */
    val statesEmptyScope: Boolean = false,
  ) : PodTokenResult

  data class Refused(val code: OAuthErrorCode, val description: String) : PodTokenResult

  /**
   * The caller must authenticate as a client before anything else is decided.
   *
   * Separate from [Refused] because it is a `401` carrying a challenge rather than an error
   * document: RFC 6749 §5.2 says an invalid client authentication answers that way, and the realm
   * is the pod, which the adapter knows and this does not.
   */
  data class ClientAuthenticationRequired(val description: String) : PodTokenResult
}
