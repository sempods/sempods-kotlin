package org.sempods.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.putNotNull
import java.time.Duration

/**
 * The `/authorize` request a user left behind when they were sent to the id-server to sign in.
 *
 * The flow used to need no such thing: the pod put its own request URI into a `return_to`
 * parameter and the id-server appended an identity token to it on the way back. That is what made
 * the id-server hand a bearer identity to any address that asked. Now the pod is an ordinary
 * relying party, and everything that must survive the round trip stays here — on the server, under
 * a `state` the pod minted and can only recognise once.
 *
 * One-time, hashed and TTL'd by [OneTimeStore]; what is specific here is only the payload.
 */
class PodLoginStateStore @Inject internal constructor(db: MongoDatabase) {

  /**
   * @param codeVerifier never travels through the browser. Presenting it at the id-server's token
   *   endpoint is what proves this is the same party that started the flow.
   * @param nonce ties the *token* to this request, so one minted for an earlier login of the same
   *   person — still signed, still unexpired — cannot stand in for it.
   * @param prompt what the client asked to have re-prompted, minus the force-reauth values that
   *   were satisfied by this very login. Carrying the original would loop the user straight back
   *   into another sign-in.
   */
  data class Pending(
    val pod: String,
    val clientId: String,
    val redirectUri: String,
    val clientState: String?,
    val scope: String?,
    val prompt: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val codeVerifier: String,
    val nonce: String,
    /**
     * The login-CSRF pin — the value the same browser must present back as a cookie. `state` alone
     * is a bearer, so without this a captured login URL completes in whoever's browser opens it.
     */
    val browserPin: String,
  )

  private val states = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_LOGIN_STATES,
    ttl = Duration.ofMinutes(15),
    write = {
      put("pod", it.pod)
      put("clientId", it.clientId)
      put("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putNotNull("scope", it.scope)
      putNotNull("prompt", it.prompt)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
      put("codeVerifier", it.codeVerifier)
      put("nonce", it.nonce)
      put("browserPin", it.browserPin)
    },
    read = {
      Pending(
        pod = getString("pod") ?: return@OneTimeStore null,
        clientId = getString("clientId") ?: return@OneTimeStore null,
        redirectUri = getString("redirectUri") ?: return@OneTimeStore null,
        clientState = getString("clientState"),
        scope = getString("scope"),
        prompt = getString("prompt"),
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
        codeVerifier = getString("codeVerifier") ?: return@OneTimeStore null,
        nonce = getString("nonce") ?: return@OneTimeStore null,
        browserPin = getString("browserPin") ?: return@OneTimeStore null,
      )
    },
  )

  /** The `state` to send to the id-server, and the key this request will be parked under. */
  fun newState(): String = states.newKey()

  fun create(state: String, pending: Pending) = states.create(state, pending)

  fun consume(state: String): Pending? = states.consume(state)
}
