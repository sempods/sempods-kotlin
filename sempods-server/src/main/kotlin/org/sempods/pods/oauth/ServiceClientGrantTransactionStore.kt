package org.sempods.pods.oauth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import java.time.Duration

/**
 * One grant consent, redeemable once. The submission takes everything from here and only the
 * ticked rows from the form, so the form cannot rewrite what the owner was shown.
 *
 * It binds one service client (identifier and registration), one grant set, the return path, and
 * the browser session that opened it — person *and* sign-in time. That is stricter than
 * [ConsentTransactionStore][org.sempods.auth.ConsentTransactionStore], which lets two sign-ins
 * coexist; this approval hands a long-lived credential access to data, so a page from before a
 * second sign-in is opened again.
 */
class ServiceClientGrantTransactionStore @Inject internal constructor(db: MongoDatabase) {

  /**
   * @param signedInAt the session's `auth_time` in epoch seconds, the cookie's precision. A renewal
   *   keeps it.
   * @param scopes the grant set the dialog offered.
   * @param requesterClientId the OAuth client that opened the dialog; the answer goes to its
   *   [redirectUri].
   */
  internal data class Transaction(
    val pod: String,
    val webId: String,
    val signedInAt: Long,
    val serviceClientId: String,
    val registrationId: String,
    val scopes: Set<String>,
    val requesterClientId: String,
    val redirectUri: String,
    val state: String?,
  )

  private val transactions = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_SERVICE_CLIENT_GRANT_TRANSACTIONS,
    // The consent screen's fifteen minutes.
    ttl = Duration.ofMinutes(15),
    write = {
      put("pod", it.pod)
      put("webId", it.webId)
      put("signedInAt", it.signedInAt)
      put("serviceClientId", it.serviceClientId)
      put("registrationId", it.registrationId)
      putStrings("scopes", it.scopes)
      put("requesterClientId", it.requesterClientId)
      put("redirectUri", it.redirectUri)
      putNotNull("state", it.state)
    },
    read = {
      Transaction(
        pod = getString("pod") ?: return@OneTimeStore null,
        webId = getString("webId") ?: return@OneTimeStore null,
        signedInAt = get("signedInAt", Number::class.java)?.toLong() ?: return@OneTimeStore null,
        serviceClientId = getString("serviceClientId") ?: return@OneTimeStore null,
        registrationId = getString("registrationId") ?: return@OneTimeStore null,
        scopes = getStringSet("scopes"),
        requesterClientId = getString("requesterClientId") ?: return@OneTimeStore null,
        redirectUri = getString("redirectUri") ?: return@OneTimeStore null,
        state = getString("state"),
      )
    },
  )

  /** Records [transaction] and answers the key the dialog's form carries. */
  internal fun issue(transaction: Transaction): String = transactions.issue(transaction)

  /** The transaction behind [key], spent in the same operation; `null` where there is none. */
  internal fun consume(key: String): Transaction? = transactions.consume(key)
}
