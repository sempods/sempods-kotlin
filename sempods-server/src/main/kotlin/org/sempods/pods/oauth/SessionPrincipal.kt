package org.sempods.pods.oauth

import java.time.Instant

/**
 * The person a pod's session cookie names, as [PodTokenIssuer.readSession] reads it.
 *
 * @param authTime when the person signed in at the id-server, which a renewal carries forward
 *   rather than resetting. Older than the cookie's own `iat` on every session that has been
 *   renewed at least once.
 */
data class SessionPrincipal(val webId: String, val alsoKnownAs: List<String>, val authTime: Instant)
