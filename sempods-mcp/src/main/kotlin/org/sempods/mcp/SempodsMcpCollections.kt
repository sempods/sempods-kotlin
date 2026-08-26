package org.sempods.mcp

import org.sempods.auth.core.SempodsAuthCoreModule
import org.sempods.mcp.core.SempodsMcpCoreModule

/**
 * Every collection the hosted MCP service writes, in one place.
 *
 * Twelve of these used to be string literals inside the `getCollection(...)` call, which is the
 * one shape the other two services had already left behind: it gave a test no way to point an
 * instance at a collection of its own, so the suites worked around it with a throwaway database
 * per class. The DAOs take the name as a parameter now, the way
 * `sempods-commons-mongo/docs/document-contract.md` §"Conventions" describes.
 *
 * **No service prefix.** The database is `sempods-mcp`, so an `mcp.` in front of every name said
 * what the database already said. The `oauth.` names are deliberately the same strings the pod
 * server and the identity service use — one concept, one spelling, three databases.
 *
 * This service holds three OAuth roles at once, and the names say which is which: it is an
 * authorization server toward AI clients (`oauth.signingKeys`, `oauth.refreshTokens`,
 * `oauth.authCodes`, `oauth.clientRegistrations`, `oauth.consentTransactions`), an OAuth client
 * toward pods ([POD_TOKENS], [CONNECTIONS], `oauth.podConnectStates`), and an OIDC relying party
 * toward the identity service (`oauth.loginStates`, `oauth.webLoginStates`).
 *
 * A name here is a contract with data on disk. Changing one is a data migration, not an edit —
 * see `docs/naming.md` §3.
 */
internal object SempodsMcpCollections {

  // --- The service's own state, keyed (user, profile, pod) ---

  /** One row per connected pod. */
  const val CONNECTIONS = "connections"

  /** The token vault — pod tokens, encrypted at rest under `MCP_SECRET_KEY`. */
  const val POD_TOKENS = "podTokens"

  /** Explicitly created named profiles; the default profile is implicit and never stored. */
  const val PROFILES = "profiles"

  /** Best-effort singleton leases across replicas. Runtime state, not data. */
  const val LEASES = "leases"

  /** The retention-bounded audit trail. */
  const val AUDIT_LOG = "auditLog"

  // --- OAuth ---

  /** The keys this service signs its own access tokens with, encrypted at rest. */
  const val OAUTH_SIGNING_KEYS = "oauth.signingKeys"

  /** Refresh-token families of the AI clients. */
  const val OAUTH_REFRESH_TOKENS = "oauth.refreshTokens"

  /** RFC 7591 registrations of the AI clients, scoped per profile. */
  const val OAUTH_CLIENT_REGISTRATIONS = "oauth.clientRegistrations"

  /** The parked `/authorize` of an AI client, held across the sign-in round trip. */
  const val OAUTH_LOGIN_STATES = "oauth.loginStates"

  /** The consent screen's context. */
  const val OAUTH_CONSENT_TRANSACTIONS = "oauth.consentTransactions"

  /** The `/_system/ui` sign-in round trip — a person's session, not a client's. */
  const val OAUTH_WEB_LOGIN_STATES = "oauth.webLoginStates"

  /** CSRF state of the pod-connect round trip, with the PKCE verifier encrypted. */
  const val OAUTH_POD_CONNECT_STATES = "oauth.podConnectStates"

  /** Authorization codes. Owned by `:sempods-auth-core`; named there so all three services agree. */
  const val OAUTH_AUTH_CODES = SempodsAuthCoreModule.AUTHORIZATION_CODES

  /** The `authorize(reauthorize=true)` challenge. Owned by `:sempods-mcp-core`, same reason. */
  const val OAUTH_REAUTH_CHALLENGES = SempodsMcpCoreModule.REAUTHORIZE_CHALLENGES

  /** The whole set, for the test that pins it. */
  val ALL = listOf(
    CONNECTIONS,
    POD_TOKENS,
    PROFILES,
    LEASES,
    AUDIT_LOG,
    OAUTH_SIGNING_KEYS,
    OAUTH_REFRESH_TOKENS,
    OAUTH_CLIENT_REGISTRATIONS,
    OAUTH_LOGIN_STATES,
    OAUTH_CONSENT_TRANSACTIONS,
    OAUTH_WEB_LOGIN_STATES,
    OAUTH_POD_CONNECT_STATES,
    OAUTH_AUTH_CODES,
    OAUTH_REAUTH_CHALLENGES,
  )
}
