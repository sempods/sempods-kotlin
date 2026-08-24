package org.sempods.auth

import org.sempods.auth.core.SempodsAuthCoreModule

/**
 * Every collection the identity service writes, in one place.
 *
 * This service was the odd one out: `webid_profiles`, `signing_keys`, `login_states`,
 * `auth_codes` — snake_case where the pod server and the hosted MCP service both used
 * `prefix.camelCase`, and four spellings nobody had a reason for. What it had right was the
 * absence of a service prefix, since it already had a database of its own.
 *
 * The OAuth names below are deliberately the same strings the other two services use — one
 * concept, one spelling, three databases.
 *
 * A name here is a contract with data on disk. Changing one is a data migration, not an edit —
 * see `docs/naming.md` §3.
 */
internal object SempodsAuthCollections {

  /** The people. `_id` is the WebID URI; losing this collection loses every identity. */
  const val WEB_ID_PROFILES = "webIdProfiles"

  /** The keys this service signs `id_token`s with. */
  const val OAUTH_SIGNING_KEYS = "oauth.signingKeys"

  /** The parked `/authorize` request, carried across the Google/Apple round trip. */
  const val OAUTH_LOGIN_STATES = "oauth.loginStates"

  /** Authorization codes. Owned by `:sempods-auth-core`; named there so all three services agree. */
  const val OAUTH_AUTH_CODES = SempodsAuthCoreModule.AUTHORIZATION_CODES

  /** The whole set, for the test that pins it. */
  val ALL = listOf(
    WEB_ID_PROFILES,
    OAUTH_SIGNING_KEYS,
    OAUTH_LOGIN_STATES,
    OAUTH_AUTH_CODES,
  )
}
