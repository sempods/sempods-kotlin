package org.sempods.mcp.oauth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import java.time.Duration
import org.sempods.mcp.SempodsMcpCollections

/**
 * The AI client's `/authorize` request, parked while the person signs in at the id-server.
 *
 * The opaque `state` sent upstream binds to the captured [PendingAuthorize] — the full flow
 * context — so the callback cannot be tricked into resuming a different request (the mix-up
 * defence the concept doc calls for).
 *
 * One-time, hashed and TTL'd by [OneTimeStore]; what is specific here is only the payload.
 */
class LoginStateStore(db: MongoDatabase) {

  /** The captured `/authorize` request, resumed verbatim after the id-server login. */
  data class PendingAuthorize(
    val profile: String,
    val clientId: String,
    val redirectUri: String,
    /** The AI client's own `state` — echoed back to it unchanged on the final redirect. */
    val clientState: String?,
    val scopes: Set<String>,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    /**
     * A secret minted at `/authorize` and set as an httpOnly cookie on the login redirect. The
     * `/oidc/callback` requires the SAME browser to present it, so a login URL captured by one party
     * cannot be completed in a victim's browser (login-CSRF / session-fixation): the opaque `state`
     * alone is bearer, this second factor pins the flow to the browser that started it.
     */
    val browserNonce: String,
    /**
     * The PKCE verifier and nonce of the *upstream* flow — this service as a client of the
     * id-server. Not to be confused with [codeChallenge] above, which belongs to the AI client's
     * flow toward this service: two OIDC round trips overlap here, and each has its own pair.
     *
     * The verifier never travels through a browser; presenting it at the id-server's token
     * endpoint is what proves this is the same party that started the flow.
     */
    val oidcCodeVerifier: String,
    val oidcNonce: String,
  )

  private val states = OneTimeStore(
    db = db,
    collectionName = SempodsMcpCollections.OAUTH_LOGIN_STATES,
    ttl = Duration.ofMinutes(15),
    write = {
      put("profile", it.profile)
      put("clientId", it.clientId)
      put("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putStrings("scopes", it.scopes)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
      put("browserNonce", it.browserNonce)
      put("oidcCodeVerifier", it.oidcCodeVerifier)
      put("oidcNonce", it.oidcNonce)
    },
    read = {
      PendingAuthorize(
        profile = getString("profile") ?: return@OneTimeStore null,
        clientId = getString("clientId") ?: return@OneTimeStore null,
        redirectUri = getString("redirectUri") ?: return@OneTimeStore null,
        clientState = getString("clientState"),
        scopes = getStringSet("scopes"),
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
        browserNonce = getString("browserNonce") ?: return@OneTimeStore null,
        oidcCodeVerifier = getString("oidcCodeVerifier") ?: return@OneTimeStore null,
        oidcNonce = getString("oidcNonce") ?: return@OneTimeStore null,
      )
    },
  )

  /** The `state` to send to the id-server, and the key this request will be parked under. */
  fun newState(): String = states.newKey()

  fun create(state: String, pending: PendingAuthorize): String = state.also { states.create(it, pending) }

  fun consume(state: String): PendingAuthorize? = states.consume(state)
}
