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

/**
 * OAuth scope literal by which a client says it needs a durable connection — a sempods extension
 * and not the OIDC scope of the same name: it is requested bare, without `openid`, and this pod
 * issues no `id_token`. Top-level for the same reason as [PUBLIC_READ_SCOPE]: the validator
 * classifies it, `PodOAuthMetadataEndpoint` advertises it, and neither owns it.
 */
const val OFFLINE_ACCESS_SCOPE = "offline_access"

/**
 * OAuth scope literal by which a program asks to install a service client on this pod. It registers
 * exactly one and holds no permission on the owner's data; the grants that service ends up with
 * come from a consent of their own afterwards.
 *
 * Top-level for the same reason as [PUBLIC_READ_SCOPE]: the validator classifies it, the consent
 * screen offers it, [GrantStorePodAuthorizer] refuses context permissions to a token carrying it,
 * and no one of them owns it.
 *
 * It is the first of [PodScopeValidator.privilegedFeatureScopes], where the rules that follow from
 * being one live.
 */
const val SERVICE_CLIENTS_INSTALL_SCOPE = "service-clients:install"

/**
 * OAuth scope literal by which a program asks to list, rotate, narrow and revoke the service
 * clients already on this pod. It grants nothing and reaches no data.
 *
 * Apart from [SERVICE_CLIENTS_INSTALL_SCOPE]: an installer approved for one service must not rotate the
 * secret of another that holds `#manage` and inherit its access. The second of
 * [PodScopeValidator.privilegedFeatureScopes].
 */
const val SERVICE_CLIENTS_MANAGE_SCOPE = "service-clients:manage"

/**
 * Whether this bearer carries an authority granted for one named operation.
 *
 * Three routes ask, and none of them resolves a context: owner recognition, the gate that asks only
 * for an app, and the MCP call that ends what a client holds. An empty sandbox answers none of
 * them, because none of them looks at one — so what such a token may do is the scope it carries,
 * and the question has one owner here rather than three spellings of the same `any { }`.
 *
 * See [PodScopeValidator.privilegedFeatureScopes].
 */
val SempodsCredentials.carriesPrivilegedFeature: Boolean
  get() = oauthScopes.any { it in PodScopeValidator.privilegedFeatureScopes }

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
    val permission = ScopePermission.of(permissionRaw)
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
    val oidcScopes: Set<String> = setOf("openid", OFFLINE_ACCESS_SCOPE)

    /**
     * Stable, coarse feature/capability scopes that are NOT per-context grants and do not
     * follow the `<context-uri>#<permission>` grammar. `public-read` and the two privileged ones today;
     * `ai` / `search` and similar capability gates may be added here. Keeping this an explicit
     * allow-list is what lets the validator tell a legitimate feature scope from a typo now that
     * access tokens carry only feature scopes (context permissions resolve server-side). See
     * sempods-spec `spec/core/grants.md` ("Why context permissions are resolved server-side").
     */
    val featureScopes: Set<String> = setOf(PUBLIC_READ_SCOPE, SERVICE_CLIENTS_INSTALL_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE)

    /**
     * The feature scopes an authorization holds only because this request asked for them.
     *
     * Five rules follow, and every site reads them from here instead of naming the literal again:
     * the consent screen offers such a scope only where the request names it and never pre-ticked;
     * the submission stores no grant for it; auto-grant cannot re-issue it; a rotation drops it;
     * and a token carrying one resolves no context permissions at all ([GrantStorePodAuthorizer]).
     * Together they keep an installation authority inside the one authorization it was granted in.
     *
     * `public-read` stays outside this set. It is additive and unprivileged, and its stored grant
     * is what lets a reconnect skip the dialog.
     */
    val privilegedFeatureScopes: Set<String> = setOf(SERVICE_CLIENTS_INSTALL_SCOPE, SERVICE_CLIENTS_MANAGE_SCOPE)
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
  ;

  companion object {
    /** The permission [value] spells, or `null` where the grant grammar has no such permission. */
    fun of(value: String): ScopePermission? = entries.firstOrNull { it.value == value }
  }
}
