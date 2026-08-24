package org.sempods.mcp.oauth

import com.mongodb.client.MongoDatabase
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import java.time.Duration
import org.sempods.mcp.SempodsMcpCollections

/**
 * Who is at the consent screen, and what they are being asked to allow.
 *
 * After the id-server round trip the verified WebID and the full authorize context are captured
 * here, and the consent page carries **only** the opaque transaction id. The identity token never
 * rides through the browser form — so a leaked short-lived one cannot be replayed into the consent
 * POST to mint a long-lived service refresh token. The POST resolves user, scopes, PKCE and client
 * strictly from what is held here.
 *
 * One-time, hashed and TTL'd by [OneTimeStore]; what is specific here is only the payload and the
 * length of the window.
 */
class ConsentTransactionStore(db: MongoDatabase) {

  data class Transaction(
    val user: String,
    val profile: String,
    val clientId: String,
    val clientLabel: String,
    val redirectUri: String,
    val clientState: String?,
    val scopes: Set<String>,
    val codeChallenge: String,
    val codeChallengeMethod: String,
  )

  private val transactions = OneTimeStore(
    db = db,
    collectionName = SempodsMcpCollections.OAUTH_CONSENT_TRANSACTIONS,
    // The consent screen doubles as the inline pod-management surface (connect / verify /
    // disconnect a pod before "Allow"), which can take a while. `touch` slides this on every
    // return from a pod-connect round-trip, so an active management session never expires while
    // work is happening — the base TTL only bounds a genuinely idle screen.
    ttl = Duration.ofMinutes(15),
    write = {
      put("user", it.user)
      put("profile", it.profile)
      put("clientId", it.clientId)
      put("clientLabel", it.clientLabel)
      put("redirectUri", it.redirectUri)
      putNotNull("clientState", it.clientState)
      putStrings("scopes", it.scopes)
      put("codeChallenge", it.codeChallenge)
      put("codeChallengeMethod", it.codeChallengeMethod)
    },
    read = {
      Transaction(
        user = getString("user") ?: return@OneTimeStore null,
        profile = getString("profile") ?: return@OneTimeStore null,
        clientId = getString("clientId") ?: return@OneTimeStore null,
        clientLabel = getString("clientLabel") ?: return@OneTimeStore null,
        redirectUri = getString("redirectUri") ?: return@OneTimeStore null,
        clientState = getString("clientState"),
        scopes = getStringSet("scopes"),
        codeChallenge = getString("codeChallenge") ?: return@OneTimeStore null,
        codeChallengeMethod = getString("codeChallengeMethod") ?: return@OneTimeStore null,
      )
    },
  )

  fun create(transaction: Transaction): String = transactions.issue(transaction)

  /** Returns and removes the transaction if present and unexpired (one-time use). */
  fun consume(id: String): Transaction? = transactions.consume(id)

  /**
   * Returns the transaction **without** removing it. Used to re-render the consent screen across
   * an inline pod-connect round-trip: it must survive the browser's detour through the pod's
   * OAuth and is spent only when the user finally clicks "Allow".
   */
  fun peek(id: String): Transaction? = transactions.peek(id)

  /** Like [peek], but resets the clock — so a screen someone is working on does not expire. */
  fun touch(id: String): Transaction? = transactions.touch(id)
}
