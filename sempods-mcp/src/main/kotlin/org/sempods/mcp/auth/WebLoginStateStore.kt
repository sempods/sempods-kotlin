package org.sempods.mcp.auth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.BrowserSignIn
import org.sempods.auth.core.SecretCipher
import org.sempods.mcp.SempodsMcpCollections

/**
 * The `/_system/ui` sign-in at the id-server, parked while the browser is there: where the person
 * was going. The round trip — `state`, browser pin, verifier, nonce, `iss` — is [BrowserSignIn]'s.
 */
class WebLoginStateStore(db: MongoDatabase, cipher: SecretCipher) {

  data class Pending(val next: String)

  val signIn = BrowserSignIn<Pending>(
    db = db,
    collectionName = SempodsMcpCollections.OAUTH_WEB_LOGIN_STATES,
    cipher = cipher,
    write = { put("next", it.next) },
    read = { getString("next")?.let(::Pending) },
  )
}
