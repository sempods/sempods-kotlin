package org.sempods.auth.login

import com.mongodb.client.MongoDatabase
import org.sempods.auth.SempodsAuthCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.auth.login.StateStore.Companion.putPendingAuthorize
import org.sempods.auth.login.StateStore.Companion.readPendingAuthorize
import java.time.Duration

/**
 * The authorization request held while the agreement screen is shown: the person has signed in,
 * and the client asked for claims they have not agreed to release to it.
 *
 * The key is the hidden field of the screen's form, so it is the form's CSRF token as well: it is
 * unguessable, appears only in the page served to the browser that signed in, and is spent on
 * first use. A request with an address forged by another site has no key to send.
 */
class ConsentTransactionStore(db: MongoDatabase, collectionName: String = SempodsAuthCollections.OAUTH_CONSENT_TRANSACTIONS) {

  data class PendingConsent(
    /** The WebID that signed in. */
    val webId: String,
    val authorize: StateStore.PendingAuthorize,
  )

  private val transactions = OneTimeStore(
    db = db,
    collectionName = collectionName,
    ttl = Duration.ofMinutes(10),
    write = {
      put("webId", it.webId)
      putPendingAuthorize(it.authorize)
    },
    read = {
      PendingConsent(
        webId = getString("webId") ?: return@OneTimeStore null,
        authorize = readPendingAuthorize() ?: return@OneTimeStore null,
      )
    },
  )

  fun park(pending: PendingConsent): String = transactions.issue(pending)

  /** The parked request, removed in the same operation. `null` if unknown, used, expired or unreadable. */
  fun consume(key: String): PendingConsent? = transactions.consume(key)
}
