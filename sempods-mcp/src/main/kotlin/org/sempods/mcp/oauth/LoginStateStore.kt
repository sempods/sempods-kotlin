package org.sempods.mcp.oauth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.BrowserSignIn
import org.sempods.auth.core.SecretCipher
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.sempods.mcp.SempodsMcpCollections

/**
 * The AI client's `/authorize` request, parked while the person signs in at the id-server.
 *
 * The `state` sent upstream binds to the captured [PendingAuthorize] — the full flow context — so
 * the callback cannot be tricked into resuming a different request (the mix-up defence the concept
 * doc calls for). The round trip itself is [BrowserSignIn]'s.
 */
class LoginStateStore(db: MongoDatabase, cipher: SecretCipher) {

  /**
   * The captured `/authorize` request, resumed verbatim after the id-server login.
   *
   * [codeChallenge] belongs to the AI client's flow toward this service. Two OAuth round trips
   * overlap here, and this service's sign-in at the id-server keeps its own PKCE pair.
   */
  data class PendingAuthorize(
    val profile: String,
    val clientId: String,
    val redirectUri: String,
    /** The AI client's own `state` — echoed back to it unchanged on the final redirect. */
    val clientState: String?,
    val scopes: Set<String>,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
  )

  val signIn = BrowserSignIn<PendingAuthorize>(
    db = db,
    collectionName = SempodsMcpCollections.OAUTH_LOGIN_STATES,
    cipher = cipher,
    write = {
      put("profile", it.profile)
      put("clientId", it.clientId)
      put("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putStrings("scopes", it.scopes)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
    },
    read = {
      PendingAuthorize(
        profile = getString("profile") ?: return@BrowserSignIn null,
        clientId = getString("clientId") ?: return@BrowserSignIn null,
        redirectUri = getString("redirectUri") ?: return@BrowserSignIn null,
        clientState = getString("clientState"),
        scopes = getStringSet("scopes"),
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
      )
    },
  )
}
