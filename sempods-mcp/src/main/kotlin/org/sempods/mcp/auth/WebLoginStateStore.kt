package org.sempods.mcp.auth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.OneTimeStore
import java.time.Duration
import org.sempods.mcp.SempodsMcpCollections

/**
 * The `/_system/ui` login round-trip to the id-server: where the person was going, and the two
 * values that tie the token that comes back to the request that asked for it.
 *
 * One-time, hashed and TTL'd by [OneTimeStore]; what is specific here is only the payload.
 */
class WebLoginStateStore(db: MongoDatabase) {

  /**
   * [codeVerifier] never travels through the browser — presenting it at the id-server's token
   * endpoint is what proves this is the same party that started the flow. [nonce] ties the token
   * to *this* request, so one minted for an earlier login of the same person cannot stand in.
   */
  data class Pending(
    val next: String,
    val codeVerifier: String,
    val nonce: String,
    /**
     * The login-CSRF pin — see [org.sempods.mcp.auth.LoginCsrfPin]. `state` is a bearer, so a
     * login URL captured by one party would otherwise complete in whoever's browser it is opened
     * in, handing that person a session for the attacker's identity.
     */
    val browserNonce: String,
  )

  private val states = OneTimeStore(
    db = db,
    collectionName = SempodsMcpCollections.OAUTH_WEB_LOGIN_STATES,
    ttl = Duration.ofMinutes(15),
    write = {
      put("next", it.next)
      put("codeVerifier", it.codeVerifier)
      put("nonce", it.nonce)
      put("browserNonce", it.browserNonce)
    },
    read = {
      Pending(
        next = getString("next") ?: return@OneTimeStore null,
        codeVerifier = getString("codeVerifier") ?: return@OneTimeStore null,
        nonce = getString("nonce") ?: return@OneTimeStore null,
        browserNonce = getString("browserNonce") ?: return@OneTimeStore null,
      )
    },
  )

  /** The `state` to send to the id-server, and the key this flow will be parked under. */
  fun newState(): String = states.newKey()

  fun create(state: String, pending: Pending): String = state.also { states.create(it, pending) }

  fun consume(state: String): Pending? = states.consume(state)
}
