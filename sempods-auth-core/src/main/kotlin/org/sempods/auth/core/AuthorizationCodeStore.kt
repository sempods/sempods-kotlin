package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import java.time.Duration

/**
 * Authorization codes: one-time, short-lived, and bound to everything the exchange must re-check.
 *
 * The mechanism is [OneTimeStore]'s — the code is stored only as its SHA-256, [consume] is an
 * atomic `findOneAndDelete`, expiry is re-checked on read because the TTL reaper runs on its own
 * schedule, and the rows survive a deploy between `/authorize` and `/token`. What is specific here
 * is the payload and the five-minute window.
 *
 * @param collectionName each service keeps its own collection — the codes of one are meaningless
 *   to another, and a shared collection would be a coupling nothing needs.
 */
class AuthorizationCodeStore(db: MongoDatabase, collectionName: String) {

  /**
   * @param realm which tenant the code belongs to — a pod name for the pod server, a profile for
   *   the hosted service, the issuer itself for a single-tenant one. The token endpoint compares
   *   it, so a code minted for one tenant cannot be redeemed at another.
   * @param scopes what the token may carry. For the pod server these are feature scopes only:
   *   context permissions are persisted as grants at consent time and resolved per request, so
   *   carrying them here would be a second, staler source of the same truth.
   * @param nonce OIDC Core §3.1.2.1 — echoed into the `id_token` so a client can tie the response
   *   to the request it made. `null` for a plain OAuth exchange.
   */
  data class Entry(
    val subject: String,
    val realm: String,
    val clientId: String,
    val scopes: Set<String>,
    val redirectUri: String,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val nonce: String?,
    /**
     * Which consent this code was issued under, for a server that versions its consent; null where
     * the concept does not apply. Carried so a redemption can be refused once the person has
     * answered again — a code is a request, and it must not pick up an authority granted after it.
     */
    val consentGeneration: Long? = null,
  )

  /**
   * The wire format is unchanged from when this store wrote its own documents, and that is not
   * cosmetic: a rolling deploy leaves codes in flight, and they stay redeemable because the `_id`
   * hash, the field names and `expiresAt` are all the same. `commons-mongo` pins the rest — a null
   * or empty field is omitted rather than written as BSON `null`, which matters most for `nonce`,
   * absent on every code the pod server issues.
   */
  private val codes = OneTimeStore(
    db = db,
    collectionName = collectionName,
    ttl = Duration.ofMinutes(5),
    write = {
      put("subject", it.subject)
      put("realm", it.realm)
      put("clientId", it.clientId)
      putStrings("scopes", it.scopes)
      put("redirectUri", it.redirectUri)
      putNotNull("codeChallenge", it.codeChallenge)
      putNotNull("codeChallengeMethod", it.codeChallengeMethod)
      putNotNull("nonce", it.nonce)
      putNotNull("consentGeneration", it.consentGeneration)
    },
    read = {
      Entry(
        subject = getString("subject") ?: return@OneTimeStore null,
        realm = getString("realm") ?: return@OneTimeStore null,
        clientId = getString("clientId") ?: return@OneTimeStore null,
        scopes = getStringSet("scopes"),
        redirectUri = getString("redirectUri") ?: return@OneTimeStore null,
        codeChallenge = getString("codeChallenge"),
        codeChallengeMethod = getString("codeChallengeMethod"),
        nonce = getString("nonce"),
        consentGeneration = get("consentGeneration", Number::class.java)?.toLong(),
      )
    },
  )

  fun issue(
    subject: String,
    realm: String,
    clientId: String,
    scopes: Set<String>,
    redirectUri: String,
    codeChallenge: String?,
    codeChallengeMethod: String?,
    nonce: String? = null,
    consentGeneration: Long? = null,
  ): String = codes.issue(
    Entry(
      subject = subject,
      realm = realm,
      clientId = clientId,
      scopes = scopes,
      redirectUri = redirectUri,
      codeChallenge = codeChallenge,
      codeChallengeMethod = codeChallengeMethod,
      nonce = nonce,
      consentGeneration = consentGeneration,
    ),
  )

  /**
   * The entry, removed in the same operation. `null` if unknown, already used, expired — or
   * unreadable.
   *
   * A row missing a field it should have is treated as no code rather than as a crash: the
   * document has already been deleted by the time it is parsed, so throwing would turn a bad row
   * into a 500 on a request that can only ever fail anyway.
   */
  fun consume(code: String): Entry? = codes.consume(code)
}
