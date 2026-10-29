package org.sempods.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.BrowserSignIn
import org.sempods.auth.core.SecretCipher
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
 * a `state` the pod minted and can only recognise once. The round trip itself is [BrowserSignIn]'s.
 */
class PodLoginStateStore @Inject internal constructor(db: MongoDatabase, cipher: SecretCipher) {

  internal val signIn = BrowserSignIn<PendingLogin>(
    db = db,
    collectionName = SempodsCollections.OAUTH_LOGIN_STATES,
    cipher = cipher,
    ttl = TTL,
    write = {
      put("clientId", it.clientId)
      putNotNull("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putNotNull("scope", it.scope)
      putNotNull("prompt", it.prompt)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
      if (it.serviceConsent) put("serviceConsent", true)
    },
    read = {
      val serviceConsent = getBoolean("serviceConsent") ?: false
      PendingLogin(
        clientId = getString("clientId") ?: return@BrowserSignIn null,
        redirectUri = getString("redirectUri") ?: if (serviceConsent) null else return@BrowserSignIn null,
        clientState = getString("clientState"),
        scope = getString("scope"),
        prompt = getString("prompt"),
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
        serviceConsent = serviceConsent,
      )
    },
  )

  companion object {
    /** How long a sign-in may take; the pin cookie lives as long. */
    val TTL: Duration = BrowserSignIn.DEFAULT_TTL
  }
}
