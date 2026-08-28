package org.sempods.pods.grants

import com.google.inject.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sempods.pods.PodFacade
import org.sempods.pods.oauth.PodAccessToken
import org.sempods.spec.PodRef

/**
 * The reference implementation of [PodAuthorizer]: durable per-context grants, resolved per
 * request.
 *
 * Context permissions are **not** read from the token. They come from the grant store —
 * `PodGrantsDao` for user-delegated app tokens, `PodServiceClientDao` for service clients — through
 * [PodContextPermissionResolver], so a revoked grant or a deleted context takes effect on the next
 * request rather than after the JWT expires. Only stable feature and OIDC scopes (`public-read`,
 * `openid`) still travel in the token; they are sanitized here and carried through unchanged.
 *
 * `public-read` is **additive**: the effective contexts are the resolved grants ∪ the pod's public
 * contexts, and the second half only if the token carries the scope. The consent UI pre-checks it,
 * but a user may deselect it, and then the bearer sees its explicit grants and nothing else. See
 * `SPS-GRANT-020` (sempods-spec).
 */
class GrantStorePodAuthorizer @Inject constructor(
  private val podFacade: PodFacade,
  private val podContextPermissionResolver: PodContextPermissionResolver,
  private val podScopeValidator: PodScopeValidator,
) : PodAuthorizer {

  override fun authorize(pod: PodRef, token: PodAccessToken): SempodsCredentials {
    val podBaseUrl = podBaseUrl(pod)
    val podId = podFacade.getPodId(pod.name)

    val validScopes = sanitizeTokenScopes(token.scopeValues, pod, podBaseUrl)
    val tokenFeatureScopes = validScopes
      .filterNot { podScopeValidator.validate(it, podBaseUrl) is ScopeValidationResult.Context }
      .toSet()

    val resolved = when {
      podId == null -> ResolvedContextAccess(emptySet(), emptySet(), emptySet())
      token.isServiceClient -> podContextPermissionResolver.resolveFromServiceClient(podId, token.clientId, podBaseUrl)
      else -> podContextPermissionResolver.resolveFromGrants(podId, token.clientId, checkNotNull(token.sub), podBaseUrl)
    }

    // `oauthScopes` = manage-expanded context grants ∪ token feature scopes (authorization keys off
    // this). `oauthRawScopes` = pre-expansion grants ∪ token feature scopes (the public-read check
    // below, plus manage-source provenance in the listing surfaces).
    val effectiveScopes = resolved.effectiveScopes + tokenFeatureScopes
    val rawScopes = resolved.rawContextScopes + tokenFeatureScopes

    val publicContexts =
      if (PUBLIC_READ_SCOPE in rawScopes) podFacade.getPublicContexts(podName = pod.name) else emptySet()

    return SempodsCredentials(
      pod = pod,
      restrictedContexts = resolved.contexts + publicContexts,
      oauthClientId = token.clientId,
      oauthScopes = effectiveScopes,
      oauthRawScopes = rawScopes,
      tokenJti = token.jti,
      tokenSub = token.sub,
      tokenIssuedAt = token.issuedAt,
    )
  }

  override fun anonymous(pod: PodRef): SempodsCredentials = SempodsCredentials(
    pod = pod,
    restrictedContexts = podFacade.getPublicContexts(podName = pod.name),
    oauthClientId = null,
    oauthScopes = emptySet(),
  )

  /**
   * The pod's URI in the trailing-slash form the scope grammar compares against — a context IRI
   * must sit under `<pod-uri>/`. [PodRef.uri] is canonical without the slash; see
   * [org.sempods.SempodsUriBuilder.buildPodUri].
   */
  private fun podBaseUrl(pod: PodRef): String = "${pod.uri}/"

  /**
   * Filter a token's raw scope set to entries [PodScopeValidator] accepts — per-context scopes,
   * OIDC scopes, and recognized feature scopes. Anything else is dropped with a WARNING carrying
   * the offending value, so operators can trace which clients ship malformed scopes.
   *
   * Why this exists alongside `PodServiceClientStore`'s registration-time validation: a JWT might
   * have been signed before that gate existed, or have arrived via a code-grant path whose
   * persistence is also defective. Every authenticated request flows through here, so the
   * downstream scope sets are guaranteed-clean for `PodContextWriteAuthorizer`, the MCP listings
   * and `_system/contexts` alike.
   */
  private fun sanitizeTokenScopes(rawScopes: Set<String>, pod: PodRef, podBaseUrl: String): Set<String> =
    rawScopes.filterTo(mutableSetOf()) { scope ->
      when (val parsed = podScopeValidator.validate(scope, podBaseUrl)) {
        is ScopeValidationResult.Context,
        is ScopeValidationResult.Oidc,
        is ScopeValidationResult.Feature -> true

        is ScopeValidationResult.Invalid -> {
          logger.warn {
            "[oauth/access] Dropping invalid scope from token: pod='${pod.name}', " +
                "scope='$scope', reason='${parsed.reason}'"
          }
          false
        }
      }
    }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
