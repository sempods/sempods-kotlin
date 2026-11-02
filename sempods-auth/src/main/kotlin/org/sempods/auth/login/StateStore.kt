package org.sempods.auth.login

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.sempods.auth.SempodsAuthCollections
import org.sempods.auth.core.OneTimeStore
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import java.time.Duration

/**
 * The authorization request this service is holding while the person is away at Google or Apple.
 *
 * The `state` handed to the upstream provider is the key: it comes back on the callback and is the
 * only thing tying that answer to the request that started it. Consumed on first use, so a replayed
 * callback finds nothing.
 *
 * It used to carry a second continuation — a `return_to` address that the legacy `/login` appended
 * an identity token to. With that endpoint gone there is one kind of parked request, so a row that
 * cannot be read as one is no row at all: [consume] answers `null` and the callback refuses. Rows
 * written by the old flow fall into exactly that case, and the ten-minute TTL clears them.
 *
 * One-time, hashed and TTL'd by [OneTimeStore]. It used to keep its rows in a `ConcurrentHashMap`,
 * so every login in flight died with the process and a second replica never saw them at all — the
 * authorization codes this feeds were already durable, and this was the remaining half.
 */
class StateStore @Inject constructor(db: MongoDatabase) {

  /**
   * Kept server-side rather than round-tripped through the browser: everything here decides who
   * may receive the resulting token, so a value the browser could edit would decide it too.
   */
  data class PendingAuthorize(
    val clientId: String,
    val redirectUri: String,
    /** The client's own `state`, echoed back untouched. Not this service's CSRF token. */
    val clientState: String?,
    val nonce: String?,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val scopes: Set<String>,
    /** `prompt=consent`: ask about the released claims again even where the person agreed before. */
    val promptConsent: Boolean = false,
  )

  private val states = OneTimeStore(
    db = db,
    collectionName = SempodsAuthCollections.OAUTH_LOGIN_STATES,
    ttl = Duration.ofMinutes(10),
    write = { putPendingAuthorize(it) },
    read = { readPendingAuthorize() },
  )

  fun generate(pending: PendingAuthorize): String = states.issue(pending)

  /** The parked request, removed in the same operation. `null` if unknown, used, expired or unreadable. */
  fun consume(state: String): PendingAuthorize? = states.consume(state)

  companion object {

    /** The row shape of a [PendingAuthorize], shared with [ConsentTransactionStore], which parks one too. */
    internal fun Document.putPendingAuthorize(pending: PendingAuthorize) {
      put("clientId", pending.clientId)
      put("redirectUri", pending.redirectUri)
      putNotNull("clientState", pending.clientState)
      putNotNull("nonce", pending.nonce)
      put("codeChallenge", pending.codeChallenge)
      put("codeChallengeMethod", pending.codeChallengeMethod)
      putStrings("scopes", pending.scopes)
      if (pending.promptConsent) put("promptConsent", true)
    }

    internal fun Document.readPendingAuthorize(): PendingAuthorize? =
      PendingAuthorize(
        clientId = getString("clientId") ?: return null,
        redirectUri = getString("redirectUri") ?: return null,
        clientState = getString("clientState"),
        nonce = getString("nonce"),
        codeChallenge = getString("codeChallenge") ?: return null,
        codeChallengeMethod = getString("codeChallengeMethod") ?: return null,
        scopes = getStringSet("scopes"),
        promptConsent = getBoolean("promptConsent") == true,
      )
  }
}
