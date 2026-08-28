package org.sempods.pods.grants

import com.google.inject.Inject
import org.bson.types.ObjectId
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.oauth.serviceclients.persist.PodServiceClientDao
import java.net.URI

/**
 * Single source of truth for sempods context-permission resolution — in particular the
 * slash-delimited `<root>#manage` semantics from sempods-spec `spec/core/grants.md`
 * §"manage semantics".
 *
 * Consolidates logic that previously lived in two places and could drift:
 * - read path: [expandManageCascade] (expand `<R>#manage` into the per-descendant context set so
 *   reads/listings see what writes can reach), called from [GrantStorePodAuthorizer], and
 * - write path: `PodContextWriteAuthorizer.isCoveredByManageScope()` (decide whether a
 *   `<R>#manage` scope authorizes a single write target).
 *
 * Both reduce to the same rule: a manage root R covers context C iff `C == R` or
 * `C.startsWith("$R/")`. Routing through [PodScopeValidator] (rather than raw string ops) is
 * load-bearing, and it is what keeps that rule from having a wildcard: every context IRI lies
 * under `<pod>/_system/contexts/`, so *any* ancestor of that namespace would match all of them —
 * the pod root, `<pod>/_system`, the namespace itself. The validator refuses the whole family, so
 * none of them can match here. The check lives there rather than in `covers()` because the
 * validator is also on the read path: a wildcard that somehow reached the grant store is filtered
 * out on the way back in, not merely refused at registration.
 *
 * Also resolves request-time grants (`PodGrantsDao` / `PodServiceClientDao`) into effective
 * context permissions for slim tokens.
 *
 * TODO: the `scopes` naming here predates the token slimming — `effectiveScopes` /
 *   `rawContextScopes` hold *grant strings* (`<context>#read|write|manage`), which are server-side
 *   policy and never travel in a token, unlike the OAuth feature scopes they sit next to. Rename
 *   when touching this class; sempods-spec `spec/core/grants.md` §"Terminology" has the split.
 */
class PodContextPermissionResolver @Inject constructor(
  private val podContextsDao: PodContextsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podServiceClientDao: PodServiceClientDao,
  private val podScopeValidator: PodScopeValidator,
) {

  /**
   * Resolve a user-delegated app token's effective context permissions from the durable
   * grant store (`PodGrantsDao`), keyed by `(pod, clientId, webId)`. Slim access tokens no
   * longer carry context scopes; this is the request-time source of truth, so a grant
   * revoked in the DB takes effect on the next request.
   *
   * Note this resolves the *app-delegation* level only. The user level
   * (`PodWebIdGrantsDao`, owner-granted WebID→context access) is applied once at consent time and
   * is deliberately not re-intersected here — a request carries a single identity URI (`sub`), so
   * an intersection would drop grants made under an equivalent one, and it would add a second
   * grant-store round-trip to every authenticated request. Owner-level revocation cascades into
   * this store instead; see [PodGrantsFacade].
   */
  internal fun resolveFromGrants(
    podId: ObjectId,
    clientId: String,
    webId: String,
    podBaseUrl: String,
  ): ResolvedContextAccess {
    val rawGrants = podGrantsDao.fetchGrantStrings(podId, clientId, listOf(webId))
    return resolveContextScopes(rawGrants, podId, podBaseUrl)
  }

  /**
   * Resolve a service-client (`client_credentials`) token's effective context permissions
   * from its static registration (`PodServiceClientDao`). Service tokens are slim too; their
   * registered scopes are the request-time source, so a registration edited/cascaded away
   * (e.g. context deletion) takes effect on the next request.
   */
  internal fun resolveFromServiceClient(
    podId: ObjectId,
    clientId: String,
    podBaseUrl: String,
  ): ResolvedContextAccess {
    val registered = podServiceClientDao.findByClientId(podId, clientId)?.scopes ?: emptySet()
    return resolveContextScopes(registered, podId, podBaseUrl)
  }

  /**
   * Shared core: keep only valid context grant strings, expand `<root>#manage` cascades, and
   * derive the readable context URIs. [ResolvedContextAccess.rawContextScopes] are the
   * pre-expansion grants (used to tag manage- vs. direct-grant provenance).
   */
  private fun resolveContextScopes(
    rawScopes: Set<String>,
    podId: ObjectId,
    podBaseUrl: String,
  ): ResolvedContextAccess {
    val contextGrants = rawScopes
      .filter { podScopeValidator.validate(it, podBaseUrl) is ScopeValidationResult.Context }
      .toSet()
    val effectiveScopes = expandManageCascade(contextGrants, podId, podBaseUrl)
    val contexts = effectiveScopes
      .mapNotNull { (podScopeValidator.validate(it, podBaseUrl) as? ScopeValidationResult.Context)?.contextUri }
      .map { URI(it) }
      .toSet()
    return ResolvedContextAccess(
      rawContextScopes = contextGrants,
      effectiveScopes = effectiveScopes,
      contexts = contexts,
    )
  }

  /**
   * Expand `<R>#manage` scopes in [scopes] into per-descendant `<C>#read|write|manage`
   * synthetic scopes for every registered context C the manage root covers. Only contexts
   * that exist in [PodContextsDao] are expanded, so descendants registered after a token was
   * minted become visible on the next request without a token refresh, and unregistered
   * prefix-matches stay out.
   *
   * Returns [scopes] unchanged when no `#manage` scopes are present, so plain user tokens and
   * public-read sessions skip the DAO round-trip entirely.
   */
  internal fun expandManageCascade(scopes: Set<String>, podId: ObjectId, podBaseUrl: String): Set<String> {
    val roots = manageRoots(scopes, podBaseUrl)
    if (roots.isEmpty()) return scopes

    val registeredContexts = podContextsDao.fetchByPod(podId).map { it.contextUri }
    val matchedDescendants = registeredContexts.filter { ctx -> roots.any { root -> covers(root, ctx) } }
    if (matchedDescendants.isEmpty()) return scopes

    val synthesized = matchedDescendants.flatMap { ctx ->
      ScopePermission.entries.map { perm -> "${ctx}#${perm.value}" }
    }
    return scopes + synthesized
  }

  /**
   * Slash-delimited manage-root coverage: `true` when one of [scopes]'s `<root>#manage`
   * scopes covers [contextUri] — the root itself or a `<root>/...` descendant.
   */
  fun isCoveredByManageScope(scopes: Set<String>, podBaseUrl: String, contextUri: URI): Boolean {
    val contextStr = contextUri.toString()
    return manageRoots(scopes, podBaseUrl).any { root -> covers(root, contextStr) }
  }

  /**
   * Build the display-oriented effective-permission view behind both `GET _system/contexts`
   * (REST) and the MCP `list_contexts` tool, so the two surfaces cannot drift.
   *
   * Inputs are passed as primitives rather than as a whole [SempodsCredentials], so a caller
   * holding only the three sets — the MCP listing does — needs no credentials object to ask:
   * - [effectiveScopes]: the caller's manage-expanded scope set (`credentials.oauthScopes`).
   * - [rawScopes]: the token's pre-expansion scopes (`credentials.oauthRawScopes`), used to
   *   tag a context's [ContextPermissionSource] as `manage` (covered by a `<root>#manage`)
   *   vs. a direct `grant`.
   * - [visibleContexts]: the caller's readable contexts (`credentials.restrictedContexts`);
   *   a visible context with no scope entry is a public read-only context.
   *
   * Permission lists are collapsed so `write`/`manage` imply `read`.
   *
   * TODO: the primitives were not originally a choice. Until S1 this class could not name
   *   [SempodsCredentials] at all — it lived in `org.sempods.api`, and this package must not depend
   *   upwards. It lives here now, so whether the two call sites read better passing credentials is
   *   a decision worth taking once rather than per call site.
   */
  fun describeEffectivePermissions(
    effectiveScopes: Set<String>,
    rawScopes: Set<String>,
    visibleContexts: Set<URI>,
    podBaseUrl: String,
  ): EffectiveContextPermissions {
    val permissionsByContext: Map<String, List<String>> = effectiveScopes
      .mapNotNull { scope ->
        (podScopeValidator.validate(scope, podBaseUrl) as? ScopeValidationResult.Context)
          ?.let { it.contextUri to it.permission }
      }
      .groupBy({ it.first }, { it.second })
      .mapValues { (_, perms) -> collapse(perms.toSet()) }

    val rawManageRoots = manageRoots(rawScopes, podBaseUrl)
    val byContext = visibleContexts.map { it.toString() }.sorted().associateWith { ctx ->
      val perms = permissionsByContext[ctx]
      when {
        perms == null -> ContextPermissionEntry(ctx, listOf("read"), ContextPermissionSource.PUBLIC)
        rawManageRoots.any { root -> covers(root, ctx) } ->
          ContextPermissionEntry(ctx, perms, ContextPermissionSource.MANAGE)

        else -> ContextPermissionEntry(ctx, perms, ContextPermissionSource.GRANT)
      }
    }
    val writableContexts = permissionsByContext.filterValues { "write" in it }.keys.sorted()
    return EffectiveContextPermissions(byContext = byContext, writableContexts = writableContexts)
  }

  /** Collapse a raw permission set so `write`/`manage` imply `read`. */
  private fun collapse(perms: Set<ScopePermission>): List<String> = buildList {
    add("read")
    if (ScopePermission.write in perms || ScopePermission.manage in perms) add("write")
    if (ScopePermission.manage in perms) add("manage")
  }

  /** Extract the context URIs of all `<root>#manage` scopes, validated. */
  private fun manageRoots(scopes: Set<String>, podBaseUrl: String): List<String> =
    scopes.mapNotNull { scope ->
      (podScopeValidator.validate(scope, podBaseUrl) as? ScopeValidationResult.Context)
        ?.takeIf { it.permission == ScopePermission.manage }
        ?.contextUri
    }

  private fun covers(root: String, contextUri: String): Boolean =
    contextUri == root || contextUri.startsWith("$root/")
}

/** Where a context's effective permissions came from, surfaced as the listing `source`. */
enum class ContextPermissionSource(val value: String) {
  /** Direct per-context grant (`<context>#read|write`). */
  GRANT("grant"),

  /** Covered by a `<root>#manage` grant via the slash-delimited rule. */
  MANAGE("manage"),

  /** Visible because the context is public (no explicit grant). */
  PUBLIC("public"),
}

data class ContextPermissionEntry(
  val contextUri: String,
  /** Collapsed permission list — `write`/`manage` imply `read`. */
  val permissions: List<String>,
  val source: ContextPermissionSource,
)

data class EffectiveContextPermissions(
  /** Visible contexts keyed by context URI, ascending. */
  val byContext: Map<String, ContextPermissionEntry>,
  /** Contexts the caller may write to, ascending. */
  val writableContexts: List<String>,
)

/** Server-resolved context access for one token, replacing token-carried context scopes. */
data class ResolvedContextAccess(
  /** Validated grant strings before manage-cascade expansion (provenance for `source`). */
  val rawContextScopes: Set<String>,
  /** Manage-expanded effective scope set (`<context>#read|write|manage`). */
  val effectiveScopes: Set<String>,
  /** Readable context URIs derived from [effectiveScopes]. */
  val contexts: Set<URI>,
)
