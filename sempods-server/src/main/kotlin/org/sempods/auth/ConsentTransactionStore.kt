package org.sempods.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.sempods.SempodsCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
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
 *
 * Coexisting screens are also why a page carries the consent it was rendered under. Single-use
 * stops the *same* page being posted twice; it does nothing about a second page opened before the
 * person disconnected the app or narrowed it, which would otherwise write its own older selection
 * back on submission. What it was rendered under is compared with what stands.
 */
class ConsentTransactionStore @Inject internal constructor(db: MongoDatabase) {

  /**
   * @param webId whose consent screen this is. Compared against the session presenting it, so a
   *   token that travelled to another browser cannot be spent there.
   * @param offeredFeatureScopes the privileged feature scopes this screen put to the person, empty
   *   on an ordinary dialog. Held here rather than in a form field because it is the same question
   *   this transaction already answers — *which screen is this* — and the answer decides how an
   *   empty submission is read: ticking nothing on an ordinary dialog ends the app's access, and
   *   ticking nothing on an installation dialog declines the installation and touches nothing.
   * @param appHeldSomething whether this app held anything for this person when the screen was
   *   rendered. The submission can see what it holds *now*; what it stood at then is gone, and it
   *   is the half the stale-page comparison needs — a page can only resurrect access the app used
   *   to have.
   */
  data class Transaction(
    val pod: String,
    val webId: String,
    val consentGeneration: Long?,
    val offeredFeatureScopes: Set<String>,
    val appHeldSomething: Boolean,
  )

  private val transactions = OneTimeStore(
    db = db,
    collectionName = SempodsCollections.OAUTH_CONSENT_TRANSACTIONS,
    // Long enough to read the screen and type a context name; short enough that an abandoned tab
    // stops being actionable. Matches the window the parked `/authorize` request already has.
    ttl = Duration.ofMinutes(15),
    write = {
      put("pod", it.pod)
      put("webId", it.webId)
      putNotNull("consentGeneration", it.consentGeneration)
      putStrings("offeredFeatureScopes", it.offeredFeatureScopes)
      putNotNull("appHeldSomething", it.appHeldSomething.takeIf { held -> held })
    },
    read = {
      Transaction(
        pod = getString("pod") ?: return@OneTimeStore null,
        webId = getString("webId") ?: return@OneTimeStore null,
        consentGeneration = get("consentGeneration", Number::class.java)?.toLong(),
        // Absent on a screen rendered before this field existed, and on every ordinary one since:
        // the empty set is what both mean.
        offeredFeatureScopes = getStringSet("offeredFeatureScopes"),
        // Absent means false, which is what a screen rendered before this field existed also
        // means: the guard it feeds only ever fires where something was there to lose.
        appHeldSomething = getBoolean("appHeldSomething", false),
      )
    },
  )

  /**
   * @param consentGeneration what this app's authorization stood at when the page was rendered, or
   *   null where nothing was recorded. Compared on submission.
   * @return the token to put in the form.
   */
  fun issue(pod: String, webId: String, consentGeneration: Long? = null): String =
    issue(pod, webId, consentGeneration, emptySet(), appHeldSomething = false)

  /**
   * The same, for a screen that puts a privileged feature scope to the person.
   *
   * An overload rather than a fourth parameter on the form above, which would replace the JVM
   * descriptor that form has always had — this module is published.
   *
   * @param offeredFeatureScopes see [Transaction.offeredFeatureScopes].
   * @param appHeldSomething see [Transaction.appHeldSomething].
   */
  fun issue(
    pod: String,
    webId: String,
    consentGeneration: Long?,
    offeredFeatureScopes: Set<String>,
    appHeldSomething: Boolean = false,
  ): String = transactions.issue(
    Transaction(pod, webId, consentGeneration, offeredFeatureScopes, appHeldSomething),
  )

  /** The screen behind the token, spent in the same operation. */
  fun consume(token: String): Transaction? = transactions.consume(token)
}
