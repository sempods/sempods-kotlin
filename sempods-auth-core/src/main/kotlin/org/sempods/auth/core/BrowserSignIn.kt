package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration

/**
 * A browser's round trip to an OpenID Provider, from the redirect out to the identity that comes
 * back: [OidcRelyingParty] for the protocol, a [OneTimeStore] for what waits in between.
 *
 * ```
 * val started = signIn.begin(relyingParty, payload = whereTheUserWasGoing)
 * setCookie(pinCookieName(started.state), started.browserPin, maxAge = signIn.ttl)
 * redirect(started.authorizationUrl)
 *
 * // …the callback arrives with ?state=&iss=&code= (or &error=)
 * clearCookie(pinCookieName(state))
 * when (val done = signIn.complete(relyingParty, BrowserSignIn.Callback(state, pinCookie, iss, code, error))) {
 *   is BrowserSignIn.Completion.SignedIn -> establishSession(done.identity, done.payload)
 *   else -> refuse(done)
 * }
 * ```
 *
 * `state` names the parked sign-in and is spent first, on every callback. It is a bearer, though:
 * a login URL captured by one party would complete in whoever's browser opens it, and that browser
 * would end up signed in as the attacker. The **browser pin** is the second factor — minted here,
 * set as a cookie by the caller, required back. The cookie is the caller's, because how one is set
 * depends on its HTTP stack; its name should carry the `state`, so that two sign-ins in one browser
 * do not overwrite each other's pin.
 *
 * What is parked holds no secret in the clear: the code verifier is encrypted under [cipher], since
 * it has to be presented again, and the pin is kept as its SHA-256, since it is only compared.
 * A row this instance cannot decrypt reads as an unknown `state`.
 *
 * The callback is checked in this order, and each step decides before the next looks: the `state`;
 * the redirect address it was begun for; the pin; the `iss` parameter (RFC 9207 §2.4, before an
 * error is believed); the provider's error; the code, exchanged over the back channel and its token
 * checked against the nonce this sign-in sent.
 *
 * @param collectionName where sign-ins are parked. Flows may share one: a sign-in whose callback
 *   arrives at a redirect address other than its own is spent there as an unknown `state`.
 * @param write and [read] carry the caller's payload, which is stored in a sub-document of its own
 *   (see [OneTimeStore] for the contract). [read] returns `null` for a payload it cannot use.
 * @param ttl how long a sign-in may take. The pin cookie should live as long.
 */
class BrowserSignIn<T>(
  db: MongoDatabase,
  collectionName: String,
  private val cipher: SecretCipher,
  write: Document.(T) -> Unit,
  read: Document.() -> T?,
  val ttl: Duration = DEFAULT_TTL,
) {

  private class Parked<T>(
    val payload: T,
    val redirectUri: String,
    val codeVerifier: String,
    val nonce: String,
    val pinSha256: String,
  )

  private val parked = OneTimeStore<Parked<T>>(
    db = db,
    collectionName = collectionName,
    ttl = ttl,
    write = {
      put(FIELD_PAYLOAD, Document().apply { write(it.payload) })
      put(FIELD_REDIRECT_URI, it.redirectUri)
      put(FIELD_CODE_VERIFIER, cipher.encrypt(it.codeVerifier))
      put(FIELD_NONCE, it.nonce)
      put(FIELD_PIN_SHA256, it.pinSha256)
    },
    read = {
      // A row an older node parked has no encrypted verifier, so it reads as expired.
      val encryptedVerifier = getString(FIELD_CODE_VERIFIER) ?: return@OneTimeStore null
      Parked(
        payload = get(FIELD_PAYLOAD, Document::class.java)?.read() ?: return@OneTimeStore null,
        redirectUri = getString(FIELD_REDIRECT_URI) ?: return@OneTimeStore null,
        codeVerifier = runCatching { cipher.decrypt(encryptedVerifier) }.getOrNull() ?: return@OneTimeStore null,
        nonce = getString(FIELD_NONCE) ?: return@OneTimeStore null,
        pinSha256 = getString(FIELD_PIN_SHA256) ?: return@OneTimeStore null,
      )
    },
  )

  /**
   * Where the browser goes, and the pin the caller sets as a cookie beside it.
   *
   * @property browserPin the cookie's value. Never stored, and never sent anywhere but that cookie.
   */
  data class Started(val authorizationUrl: String, val state: String, val browserPin: String)

  /**
   * What the provider sent the browser back with, and the pin cookie the browser presented —
   * `null` for each that was absent.
   *
   * @property iss the `iss` authorization response parameter (RFC 9207).
   */
  data class Callback(
    val state: String?,
    val presentedPin: String?,
    val iss: String?,
    val code: String?,
    val error: String?,
    val errorDescription: String? = null,
  )

  /** What a callback came to: exactly one of these. Every outcome but [UnknownState] spent the sign-in. */
  sealed interface Completion<out T> {

    /** No parked sign-in under that `state`: unknown, expired, already used, or begun elsewhere. */
    data object UnknownState : Completion<Nothing>

    /** The browser presented no pin, or another sign-in's. Nothing was exchanged. */
    data class OtherBrowser<T>(val payload: T, val pinPresented: Boolean) : Completion<T>

    /**
     * The answer does not name the provider this sign-in went to, or names none where the provider
     * promised it would ([OidcProviderMetadata.authorizationResponseIssParameterSupported]).
     * Nothing in it was acted on, an error included.
     *
     * @property presentedIssuer what arrived, `null` for nothing. A stranger's string: log it escaped.
     */
    data class WrongIssuer<T>(val payload: T, val presentedIssuer: String?) : Completion<T>

    /**
     * The sign-in ended at the provider, which answered [error] (RFC 6749 §4.1.2.1). Both values
     * are the provider's own text and reach the caller unchecked.
     */
    data class ProviderError<T>(val payload: T, val error: String, val errorDescription: String?) : Completion<T>

    /** The callback carries neither a code nor an error. */
    data class NoCode<T>(val payload: T) : Completion<T>

    /**
     * The code could not be exchanged, or the token that came back does not belong to this
     * sign-in — another nonce, another audience, a bad signature ([OidcRelyingParty.completeAuthorization]).
     */
    data class ExchangeFailed<T>(val payload: T, val cause: Exception) : Completion<T>

    /** Signed in: who the provider says this is, and what the caller parked. */
    data class SignedIn<T>(val payload: T, val identity: OidcRelyingParty.VerifiedIdentity) : Completion<T>
  }

  /**
   * Parks [payload] and answers where the browser goes.
   *
   * @param prompt forwarded to the provider when it parses as an OIDC `prompt`, else dropped.
   */
  fun begin(relyingParty: OidcRelyingParty, payload: T, prompt: String? = null): Started {
    val state = parked.newKey()
    val started = relyingParty.beginAuthorization(prompt = prompt, state = state)
    val browserPin = Secrets.newSecret()
    parked.create(
      state,
      Parked(
        payload = payload,
        redirectUri = relyingParty.redirectUri,
        codeVerifier = started.codeVerifier,
        nonce = started.nonce,
        pinSha256 = sha256Hex(browserPin),
      ),
    )
    return Started(started.authorizationUrl, state, browserPin)
  }

  /**
   * Completes the sign-in [Callback.state] names, against the [relyingParty] whose redirect
   * address the callback arrived at.
   *
   * A `state` this class could not have minted ([Secrets.isWellFormed]) is an unknown one, and is
   * not looked up. Callers that build a cookie name from it check the same shape before they do.
   */
  fun complete(relyingParty: OidcRelyingParty, callback: Callback): Completion<T> {
    // Spent first and unconditionally: a replayed callback must find nothing, whether it carries a
    // code, an error, or neither.
    val state = callback.state?.takeIf(Secrets::isWellFormed) ?: return Completion.UnknownState
    val pending = parked.consume(state) ?: return Completion.UnknownState
    if (pending.redirectUri != relyingParty.redirectUri) return Completion.UnknownState
    val payload = pending.payload

    // Before the code is exchanged: a callback opened in the wrong browser must cost nothing.
    val presentedPin = callback.presentedPin
    if (presentedPin == null || !Secrets.matches(sha256Hex(presentedPin), pending.pinSha256)) {
      return Completion.OtherBrowser(payload, pinPresented = presentedPin != null)
    }

    // Simple string comparison (RFC 9207 §2.4), and before the error: an error from another
    // provider is no more this provider's answer than a code from one.
    val metadata = relyingParty.metadata
    val issuerMatches = when (val iss = callback.iss) {
      null -> !metadata.authorizationResponseIssParameterSupported
      else -> iss == metadata.issuer
    }
    if (!issuerMatches) return Completion.WrongIssuer(payload, callback.iss)

    callback.error?.let { return Completion.ProviderError(payload, it, callback.errorDescription) }
    val code = callback.code?.trim()?.takeIf { it.isNotEmpty() } ?: return Completion.NoCode(payload)

    val identity = try {
      relyingParty.completeAuthorization(code, pending.codeVerifier, pending.nonce)
    } catch (e: Exception) {
      return Completion.ExchangeFailed(payload, e)
    }
    return Completion.SignedIn(payload, identity)
  }

  companion object {
    /** Fifteen minutes: long enough to sign in with a provider's second factor, short enough to reap. */
    @JvmField
    val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

    private const val FIELD_PAYLOAD = "payload"
    private const val FIELD_REDIRECT_URI = "redirectUri"
    private const val FIELD_CODE_VERIFIER = "encryptedCodeVerifier"
    private const val FIELD_NONCE = "nonce"
    private const val FIELD_PIN_SHA256 = "browserPinSha256"
  }
}
