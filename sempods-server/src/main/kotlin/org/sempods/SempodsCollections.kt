package org.sempods

import org.sempods.auth.core.SempodsAuthCoreModule
import org.sempods.mcp.core.SempodsMcpCoreModule

/**
 * Every collection this server writes, in one place.
 *
 * The names used to sit in thirteen DAO companions, and the set they formed was visible nowhere:
 * adding a collection was invisible in review, and the two oldest (`pods.pod`,
 * `pods.resources.backup`) had drifted out of the namespace the other eleven shared without
 * anyone noticing. [ALL] is what `SempodsCollectionsTest` pins.
 *
 * **No service prefix.** The database is `sempods-server`, so a `sempods.` in front of every name
 * said what the database already said. What the prefix does carry now is the *kind* of thing:
 * `oauth.` for the OAuth machinery, nothing for the pod's own data. The OAuth names are
 * deliberately the same strings the identity service and the hosted MCP service use — one
 * concept, one spelling, three databases.
 *
 * A name here is a contract with data on disk. Changing one is a data migration, not an edit —
 * see `docs/naming.md` §3.
 */
internal object SempodsCollections {

  // --- The pod's own data ---

  /** The pod registry — the row every other collection is keyed by. */
  const val PODS = "pods"

  /**
   * The durable RDF, as N-Quads, one row per `(pod, resource, context)`.
   *
   * It was `pods.resources.backup`, which undersold it: the MemoryStore is rebuilt from this on
   * every start, so it is the primary copy rather than a backup of one.
   */
  const val RESOURCES = "resources"

  /** Context registry, per pod. */
  const val CONTEXTS = "contexts"

  /** App-delegated grants — `(podId, appId, webId, scope)`. */
  const val GRANTS = "grants"

  /** Owner-level WebID grants, independent of any app. */
  const val WEB_ID_GRANTS = "webIdGrants"

  /** Media registry, one row per `(pod, media)`. Without it an upload is unreachable. */
  const val MEDIA = "media"

  // --- OAuth ---

  /** Service clients (`client_credentials`) — how an operator-minted caller reaches a pod. */
  const val OAUTH_SERVICE_CLIENTS = "oauth.serviceClients"

  /** Append-only audit of what those clients did. */
  const val OAUTH_SERVICE_AUDIT_LOG = "oauth.serviceAuditLog"

  /** RFC 7591 dynamic client registrations. */
  const val OAUTH_CLIENT_REGISTRATIONS = "oauth.clientRegistrations"

  /** The keys this server signs pod access tokens with. */
  const val OAUTH_SIGNING_KEYS = "oauth.signingKeys"

  /** Refresh-token families, with rotation and reuse detection. */
  const val OAUTH_REFRESH_TOKENS = "oauth.refreshTokens"

  /** The parked `/authorize` request, carried across the sign-in round trip. */
  const val OAUTH_LOGIN_STATES = "oauth.loginStates"

  /** One consent screen, once. */
  const val OAUTH_CONSENT_TRANSACTIONS = "oauth.consentTransactions"

  /** Authorization codes. Owned by `:sempods-auth-core`; named there so all three services agree. */
  const val OAUTH_AUTH_CODES = SempodsAuthCoreModule.AUTHORIZATION_CODES

  /** The `authorize(reauthorize=true)` challenge. Owned by `:sempods-mcp-core`, same reason. */
  const val OAUTH_REAUTH_CHALLENGES = SempodsMcpCoreModule.REAUTHORIZE_CHALLENGES

  /**
   * The whole set, for the test that pins it.
   *
   * Most DAOs are bound lazily, so a booted server does not necessarily hold every collection —
   * which is exactly why the guard reads this list rather than asking a running database.
   */
  val ALL = listOf(
    PODS,
    RESOURCES,
    CONTEXTS,
    GRANTS,
    WEB_ID_GRANTS,
    MEDIA,
    OAUTH_SERVICE_CLIENTS,
    OAUTH_SERVICE_AUDIT_LOG,
    OAUTH_CLIENT_REGISTRATIONS,
    OAUTH_SIGNING_KEYS,
    OAUTH_REFRESH_TOKENS,
    OAUTH_LOGIN_STATES,
    OAUTH_CONSENT_TRANSACTIONS,
    OAUTH_AUTH_CODES,
    OAUTH_REAUTH_CHALLENGES,
  )
}
