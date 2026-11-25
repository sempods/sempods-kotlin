package org.sempods.pods.grants

import org.sempods.spec.PodRef
import java.net.URI
import java.time.Instant

/**
 * What one request is allowed to do, as
 * [org.sempods.pods.authentication.spi.PodRequestAuthenticator] resolved it — by default through
 * [PodAuthorizer]. The result type every endpoint below `{pod}/…` carries from the authentication
 * down into the read and write paths.
 *
 * Lives here, next to the seam that produces it, rather than in `org.sempods.api`: it is the
 * authorization result, and a service in this package must be able to name it without an upward
 * dependency on the HTTP layer.
 *
 * [pod] is a [PodRef] and not the stored row — see [PodRef] for why. Code that needs this
 * implementation's `podId` resolves it from [PodRef.name] through `PodFacade.getPodId`, which is
 * cached.
 */
data class SempodsCredentials(
  val pod: PodRef,
  /**
   * The contexts this request may read. `null` means unrestricted and is reserved for callers
   * that bypass the sandbox by construction (see `PodMediaFacade`); an authorizer never returns it.
   */
  val restrictedContexts: Set<URI>?,
  val oauthClientId: String? = null,
  /**
   * Effective scope set used for authorization decisions: synthetic
   * `<C>#read|write|manage` entries derived from manage-cascade expansion
   * are included so the read and write paths agree. See
   * [PodContextPermissionResolver.expandManageCascade].
   */
  val oauthScopes: Set<String> = emptySet(),
  /**
   * The token's granted scopes before manage-cascade expansion, validator-sanitized. Use this on
   * surfaces that report what the client was granted (audit lines, the `authorize` tool's
   * idempotent response, the public-read upgrade check) so the count and contents do not shift
   * with the pod owner's context registry. The verbatim JWT scope claim is captured in the
   * `[oauth/access] Token verified` log line, which is DEBUG — do not rely on it being in a
   * production log.
   */
  val oauthRawScopes: Set<String> = oauthScopes,
  val tokenJti: String? = null,
  val tokenSub: String? = null,
  val tokenIssuedAt: Instant? = null,
  /**
   * Set by a [org.sempods.pods.authentication.spi.PodRequestAuthenticator] that identified a caller
   * who carries no [oauthClientId] — a session of the deployment's own, for instance. Read
   * [authenticated], not this.
   */
  val identified: Boolean = false,
) {

  /**
   * Whether the deployment identified the caller; `false` is the anonymous reader of public
   * contexts. A caller with an [oauthClientId] always is, so credentials copied from an anonymous
   * template with a client id added count as authenticated without anyone setting [identified].
   *
   * An authenticated caller names itself through [tokenSub] (its WebID) or [oauthClientId]: a
   * context or media write records that as its creator, and a context write refuses a caller that
   * names neither.
   */
  val authenticated: Boolean get() = identified || oauthClientId != null
}
