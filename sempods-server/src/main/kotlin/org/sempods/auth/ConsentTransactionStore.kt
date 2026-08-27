package org.sempods.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import java.time.Duration

/**
 * One consent screen, once.
 *
 * The token this mints is what the consent form carries, and it does two jobs that a value derived
 * from the session cannot:
 *
 * - **It is single-use.** A consent submission changes durable state — `replaceAppGrants` writes
 *   the ticked selection as *the* grant set for that app. A form that could be posted twice can
 *   therefore restore a selection the person has since narrowed: consent to A and B, later
 *   re-consent to A alone, then resubmit the old page and B is back, with a fresh authorization
 *   code to go with it.
 * - **Several can coexist.** Two sign-ins on one pod mint two sessions, and the second cookie
 *   replaces the first — so a token *tied to the session* stops matching the page that was
 *   rendered under the earlier one. A per-screen token does not care.
 *
 * Both were lost for one commit when the session's `jti` stood in for this: the session is who,
 * not which screen. It answers the first question and this answers the second, which is why the
 * consent POST checks both — a token lifted out of a page is worthless without the cookie it was
 * rendered beside.
 */
class ConsentTransactionStore @Inject internal constructor(db: MongoDatabase) {

  /**
   * @param webId whose consent screen this is. Compared against the session presenting it, so a
   *   token that travelled to another browser cannot be spent there.
   */
  data class Transaction(val pod: String, val webId: String)

  private val transactions = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_CONSENT_TRANSACTIONS,
    // Long enough to read the screen and type a context name; short enough that an abandoned tab
    // stops being actionable. Matches the window the parked `/authorize` request already has.
    ttl = Duration.ofMinutes(15),
    write = { put("pod", it.pod); put("webId", it.webId) },
    read = {
      Transaction(
        pod = getString("pod") ?: return@OneTimeStore null,
        webId = getString("webId") ?: return@OneTimeStore null,
      )
    },
  )

  /** @return the token to put in the form. */
  fun issue(pod: String, webId: String): String = transactions.issue(Transaction(pod, webId))

  /** The screen behind the token, spent in the same operation. */
  fun consume(token: String): Transaction? = transactions.consume(token)
}
