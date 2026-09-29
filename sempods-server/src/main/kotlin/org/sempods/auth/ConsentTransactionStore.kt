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
 * - **It is single-use.** A consent submission changes durable state — `PodGrantsFacade.replaceGrants`
 *   writes the ticked selection as *the* grant set for that app. A form that could be posted twice can
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
 *
 * **The screen also carries its request.** A token issued with a [Binding] holds the authorization
 * request the page answers and the rows it offered, and the submission reads both from here. A form
 * cannot then redirect the answer to another client, drop the PKCE challenge, or tick a row the
 * person was never shown.
 *
 * The same store holds the service consent's screens ([Binding.service]), under the same rules.
 *
 * A transaction without a [Binding] was written by an older node during a rollout. The submission
 * then reads the request from the form. The rule and how long it holds are in `docs/auth/oauth.md`
 * §"Authorize flow (overview)"; the next minor release removes it with the two unbound [issue]
 * overloads.
 */
class ConsentTransactionStore @Inject internal constructor(db: MongoDatabase) {

  /**
   * @param webId whose consent screen this is. Compared against the session presenting it, so a
   *   token that travelled to another browser cannot be spent there.
   * @param offeredFeatureScopes the privileged feature scopes this screen put to the person, empty
   *   on an ordinary dialog. Held here rather than in a form field because it is the same question
   *   this transaction already answers — *which screen is this* — and the answer decides how an
   *   empty submission is read: ticking nothing on an ordinary dialog ends the app's access, and
   *   ticking nothing on a privileged dialog declines the authority and touches nothing.
   * @param disconnects how many times this app's access had been ended when the screen was
   *   rendered. The submission compares it with the count standing now: a page from before an
   *   ending would hand back what the person removed, and a page that is merely older than some
   *   other answer would not.
   * @param binding the request and the rows this screen answers; `null` on a transaction an older
   *   node wrote.
   * @param consentText the version of the privileged consent text this screen rendered, which the
   *   authority the person approves records; `null` on an ordinary screen and on one an older node
   *   rendered.
   */
  data class Transaction @JvmOverloads constructor(
    val pod: String,
    val webId: String,
    val consentGeneration: Long?,
    val offeredFeatureScopes: Set<String>,
    val disconnects: Long,
    val binding: Binding? = null,
    val consentText: Int? = null,
  )

  /**
   * What the screen was rendered for, as normalized by `/authorize`.
   *
   * @param clientId the recipient, and [redirectUri] where its answer goes; `null` only on a service
   *   consent opened without one. The submission answers this client and no other.
   * @param state the client's `state`, `null` where it sent none.
   * @param codeChallenge the PKCE challenge the code will carry, and [codeChallengeMethod] its
   *   method; `null` where the request carried none.
   * @param offeredContexts the IRI of every context row the screen rendered, each with its
   *   `read`, `write` and `manage` boxes. The privileged feature scopes are
   *   [Transaction.offeredFeatureScopes].
   * @param publicReadOffered whether the screen rendered the `public-read` box.
   * @param contextCreationOffered whether the screen let the person create contexts.
   * @param service set on a service consent, `null` on a delegated one. Each submission route
   *   refuses the other's screen.
   */
  data class Binding @JvmOverloads constructor(
    val clientId: String,
    val redirectUri: String?,
    val state: String?,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val offeredContexts: Set<String>,
    val publicReadOffered: Boolean,
    val contextCreationOffered: Boolean,
    val service: ServiceRecipient? = null,
  )

  /**
   * The service registration a service consent was rendered for.
   *
   * @param registrationId the registration's own id, so a registration removed and re-created under
   *   the same `client_id` is not the one approved.
   * @param grantsVersion its grants' version when the screen was rendered. The replace writes only
   *   at this version.
   */
  data class ServiceRecipient(val registrationId: String, val grantsVersion: Long)

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
      putNotNull("disconnects", it.disconnects.takeIf { count -> count > 0 })
      putNotNull("consentText", it.consentText)
      it.binding?.let { binding ->
        put("clientId", binding.clientId)
        putNotNull("redirectUri", binding.redirectUri)
        putNotNull("state", binding.state)
        putNotNull("codeChallenge", binding.codeChallenge)
        putNotNull("codeChallengeMethod", binding.codeChallengeMethod)
        putStrings("offeredContexts", binding.offeredContexts)
        put("publicReadOffered", binding.publicReadOffered)
        put("contextCreationOffered", binding.contextCreationOffered)
        binding.service?.let { service ->
          put("serviceRegistrationId", service.registrationId)
          put("serviceGrantsVersion", service.grantsVersion)
        }
      }
    },
    read = {
      Transaction(
        pod = getString("pod") ?: return@OneTimeStore null,
        webId = getString("webId") ?: return@OneTimeStore null,
        consentGeneration = get("consentGeneration", Number::class.java)?.toLong(),
        // Absent on a screen rendered before this field existed, and on every ordinary one since:
        // the empty set is what both mean.
        offeredFeatureScopes = getStringSet("offeredFeatureScopes"),
        // Absent means none, which is what a screen rendered before this field existed also means:
        // it compares equal to a document that has never recorded an ending.
        disconnects = get("disconnects", Number::class.java)?.toLong() ?: 0L,
        consentText = get("consentText", Number::class.java)?.toInt(),
        // Absent on a transaction an older node wrote; see the class comment for how long that is
        // accepted.
        binding = getString("clientId")?.let { clientId ->
          val service = getString("serviceRegistrationId")?.let { registrationId ->
            ServiceRecipient(
              registrationId = registrationId,
              grantsVersion = get("serviceGrantsVersion", Number::class.java)?.toLong() ?: return@OneTimeStore null,
            )
          }
          Binding(
            clientId = clientId,
            // A delegated screen always has one; only a service consent may go without.
            redirectUri = getString("redirectUri") ?: if (service == null) return@OneTimeStore null else null,
            state = getString("state"),
            codeChallenge = getString("codeChallenge"),
            codeChallengeMethod = getString("codeChallengeMethod"),
            offeredContexts = getStringSet("offeredContexts"),
            publicReadOffered = getBoolean("publicReadOffered") ?: false,
            contextCreationOffered = getBoolean("contextCreationOffered") ?: false,
            service = service,
          )
        },
      )
    },
  )

  /**
   * @param consentGeneration what this app's authorization stood at when the page was rendered, or
   *   null where nothing was recorded. Compared on submission.
   * @return the token to put in the form.
   */
  @Deprecated("Unbound: accepted only for the rest of 0.2.x. Issue with a Binding.")
  fun issue(pod: String, webId: String, consentGeneration: Long? = null): String =
    transactions.issue(Transaction(pod, webId, consentGeneration, emptySet(), disconnects = 0))

  /**
   * The same, for a screen that puts a privileged feature scope to the person.
   *
   * An overload rather than a fourth parameter on the form above, which would replace the JVM
   * descriptor that form has always had — this module is published.
   *
   * @param offeredFeatureScopes see [Transaction.offeredFeatureScopes].
   * @param disconnects see [Transaction.disconnects].
   */
  @Deprecated("Unbound: accepted only for the rest of 0.2.x. Issue with a Binding.")
  fun issue(
    pod: String,
    webId: String,
    consentGeneration: Long?,
    offeredFeatureScopes: Set<String>,
    disconnects: Long = 0,
  ): String = transactions.issue(
    Transaction(pod, webId, consentGeneration, offeredFeatureScopes, disconnects),
  )

  /**
   * A screen bound to the request it answers and the rows it offers — the form `/authorize` uses.
   *
   * @param binding see [Transaction.binding].
   * @param consentText see [Transaction.consentText].
   */
  @JvmOverloads
  fun issue(
    pod: String,
    webId: String,
    consentGeneration: Long?,
    offeredFeatureScopes: Set<String>,
    disconnects: Long,
    binding: Binding,
    consentText: Int? = null,
  ): String = transactions.issue(
    Transaction(pod, webId, consentGeneration, offeredFeatureScopes, disconnects, binding, consentText),
  )

  /** The screen behind the token, spent in the same operation. */
  fun consume(token: String): Transaction? = transactions.consume(token)
}
