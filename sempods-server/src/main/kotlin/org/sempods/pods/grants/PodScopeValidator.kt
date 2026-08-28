package org.sempods.pods.grants

import org.sempods.commons.net.SempodsPodRoutes
import java.net.URI

/**
 * OAuth scope literal that requests read-only access to whatever the pod currently exposes as
 * public (`PodFacade.getPublicContexts`). Top-level and in this package because it is read on the
 * authorization path — [GrantStorePodAuthorizer] unions the public contexts in only when a token
 * carries it — and written on the consent path in `PodAuthEndpoint`; neither side owns it.
 *
 * See `SPS-GRANT-020` (sempods-spec).
 */
const val PUBLIC_READ_SCOPE = "public-read"

class PodScopeValidator {

  fun validate(scope: String, podBaseUrl: String): ScopeValidationResult {
    val normalized = scope.trim()
    if (normalized.isBlank()) {
      return ScopeValidationResult.Invalid("scope is blank")
    }

    if (normalized in oidcScopes) {
      return ScopeValidationResult.Oidc(normalized)
    }

    if (normalized in featureScopes) {
      return ScopeValidationResult.Feature(normalized)
    }

    val separatorIndex = normalized.lastIndexOf('#')
    if (separatorIndex <= 0 || separatorIndex == normalized.lastIndex) {
      return ScopeValidationResult.Invalid("scope must be '<context-uri>#<permission>'")
    }

    val contextUri = normalized.substring(0, separatorIndex)
    val permissionRaw = normalized.substring(separatorIndex + 1)
    val permission = ScopePermission.entries.firstOrNull { it.value == permissionRaw }
      ?: return ScopeValidationResult.Invalid("unsupported scope permission '$permissionRaw'")

    val context = try {
      URI(contextUri)
    } catch (_: Exception) {
      return ScopeValidationResult.Invalid("invalid context URI")
    }

    if (!context.isAbsolute) {
      return ScopeValidationResult.Invalid("context URI must be absolute")
    }

    val podBase = podBaseUrl.trimEnd('/') + "/"
    if (!contextUri.startsWith(podBase)) {
      return ScopeValidationResult.Invalid("context URI must be inside pod base '$podBase'")
    }
    // `covers()` reads a scope's context URI as a subtree root (`uri == root ||
    // uri.startsWith("$root/")`), so anything the context namespace sits *below* matches every
    // context on the pod at once. `<pod>#manage` was already refused by the check above;
    // `<pod>/_system#manage` and `<pod>/_system/contexts#manage` were not, and
    // `docs/auth/service-clients.md` promises a service client is confined to a subtree.
    //
    // Stated as "no ancestor of the namespace" rather than "must be under the namespace": the
    // second would also retire the older context shapes `ContextPathRules` records, which is a
    // different rule with a different blast radius. This one closes the wildcard completely and
    // narrows nothing else.
    val namespaceRoot = podBase + SempodsPodRoutes.CONTEXT_PATH_PREFIX.trimEnd('/')
    val candidate = contextUri.trimEnd('/')
    if (namespaceRoot == candidate || namespaceRoot.startsWith("$candidate/")) {
      return ScopeValidationResult.Invalid(
        "'$contextUri' is at or above the context namespace '$namespaceRoot', so it is not a context",
      )
    }

    return ScopeValidationResult.Context(
      raw = normalized,
      contextUri = contextUri,
      permission = permission,
    )
  }

  companion object {
    val oidcScopes: Set<String> = setOf("openid", "offline_access")

    /**
     * Stable, coarse feature/capability scopes that are NOT per-context grants and do not
     * follow the `<context-uri>#<permission>` grammar. `public-read` today; `ai` / `search`
     * and similar capability gates may be added here. Keeping this an explicit allow-list is
     * what lets the validator tell a legitimate feature scope from a typo once access tokens
     * carry only feature scopes (context permissions resolve server-side). See
     * sempods-spec `spec/core/grants.md` ("Why context permissions are resolved
     * server-side"). Which additional feature scopes to accept is still open
     * (the maintainer's internal roadmap).
     */
    val featureScopes: Set<String> = setOf(PUBLIC_READ_SCOPE)
  }
}

sealed interface ScopeValidationResult {
  data class Oidc(val scope: String) : ScopeValidationResult

  /** A recognized stable feature scope (see [PodScopeValidator.featureScopes]). */
  data class Feature(val scope: String) : ScopeValidationResult

  data class Context(
    val raw: String,
    val contextUri: String,
    val permission: ScopePermission,
  ) : ScopeValidationResult

  data class Invalid(val reason: String) : ScopeValidationResult
}

enum class ScopePermission(val value: String) {
  read("read"),
  write("write"),
  manage("manage"),
}
