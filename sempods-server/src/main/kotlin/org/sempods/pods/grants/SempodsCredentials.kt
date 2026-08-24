package org.sempods.pods.grants

import org.sempods.spec.PodRef
import java.net.URI
import java.time.Instant

/**
 * What one request is allowed to do, as [PodAuthorizer] resolved it. The result type every
 * endpoint below `{pod}/…` carries from `SempodsBaseEndpoint.authenticate` down into the read and
 * write paths.
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
)
