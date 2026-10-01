package org.sempods.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.putNotNull
import java.time.Duration

/**
 * The browser request a user left behind when they were sent to the id-server to sign in: an
 * `/authorize`, or a service consent ([PendingLogin.serviceConsent]).
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

  private val states = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_LOGIN_STATES,
    ttl = Duration.ofMinutes(15),
    write = {
      put("pod", it.pod)
      put("clientId", it.clientId)
      putNotNull("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putNotNull("scope", it.scope)
      putNotNull("prompt", it.prompt)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
      put("codeVerifier", it.codeVerifier)
      put("nonce", it.nonce)
      put("browserPin", it.browserPin)
      if (it.serviceConsent) put("serviceConsent", true)
    },
    read = {
      // A `/grant` sign-in an older node parked: that route is gone, so the row reads as expired.
      if (containsKey("serviceClient")) return@OneTimeStore null
      val serviceConsent = getBoolean("serviceConsent") ?: false
      PendingLogin(
        pod = getString("pod") ?: return@OneTimeStore null,
        clientId = getString("clientId") ?: return@OneTimeStore null,
        redirectUri = getString("redirectUri") ?: if (serviceConsent) null else return@OneTimeStore null,
        clientState = getString("clientState"),
        scope = getString("scope"),
        prompt = getString("prompt"),
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
        codeVerifier = getString("codeVerifier") ?: return@OneTimeStore null,
        nonce = getString("nonce") ?: return@OneTimeStore null,
        browserPin = getString("browserPin") ?: return@OneTimeStore null,
        serviceConsent = serviceConsent,
      )
    },
  )

  /** The `state` to send to the id-server, and the key this request will be parked under. */
  fun newState(): String = states.newKey()

  fun create(state: String, pending: PendingLogin) = states.create(state, pending)

  fun consume(state: String): PendingLogin? = states.consume(state)
}
