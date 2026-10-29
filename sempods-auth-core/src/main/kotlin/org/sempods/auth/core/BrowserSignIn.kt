package org.sempods.auth.core

import com.mongodb.client.MongoDatabase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.sempods.commons.utils.HashUtil.sha256Hex
import java.time.Duration

/**
 * A browser's round trip to an OpenID Provider: [begin] parks the caller's payload and answers
 * where the browser goes, [complete] takes the callback and answers who signed in.
 *
 * `state` is a bearer, so a captured login URL would complete in whoever's browser opens it. The
 * **browser pin** ties it to the browser that started it: the caller sets it as a cookie named
 * after the `state`, and presents it back. How a cookie is set is the caller's HTTP stack.
 *
 * The parked row holds the code verifier encrypted under [cipher] and the pin as its SHA-256. A row
 * this instance cannot decrypt, or one an older node parked, reads as an unknown `state`.
 *
 * [complete] checks, each before the next: the `state` (spent first, on every callback); the
 * redirect address it was begun for; the pin; `iss` ([OidcRelyingParty.acceptsResponseIssuer]); the
 * provider's error; the code and the token's nonce.
 *
 * @param collectionName flows may share one: a callback at a redirect address other than its own
 *   is an unknown `state` there.
 * @param write and [read] carry the payload in a sub-document ([OneTimeStore] has the contract).
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
      Parked(
        payload = get(FIELD_PAYLOAD, Document::class.java)?.read() ?: return@OneTimeStore null,
        redirectUri = getString(FIELD_REDIRECT_URI) ?: return@OneTimeStore null,
        codeVerifier = getString(FIELD_CODE_VERIFIER)?.let { encrypted ->
          runCatching { cipher.decrypt(encrypted) }
            .onFailure { logger.warn(it) { "parked sign-in will not decrypt — the key changed, or replicas disagree on it" } }
            .getOrNull()
        } ?: return@OneTimeStore null,
        nonce = getString(FIELD_NONCE) ?: return@OneTimeStore null,
        pinSha256 = getString(FIELD_PIN_SHA256) ?: return@OneTimeStore null,
      )
    },
  )

  /** @property browserPin the cookie's value. Never stored. */
  data class Started(val authorizationUrl: String, val state: String, val browserPin: String)

  /**
   * The callback's query parameters and the pin cookie, `null` for each that was absent.
   *
   * @property iss the single `iss`; [responseIssuer] reads it from every value the response carried.
   */
  data class Callback(
    val state: String?,
    val presentedPin: String?,
    val iss: String?,
    val code: String?,
    val error: String?,
    val errorDescription: String? = null,
  )

  enum class Refusal {
    /** No parked sign-in under that `state`: unknown, expired, already used, or begun elsewhere. */
    UNKNOWN_STATE,

    /** No pin, or another sign-in's. */
    OTHER_BROWSER,

    /** `iss` names another provider, or none where the provider promised one. */
    WRONG_ISSUER,
  }

  /** What a callback came to. Every outcome but [Refusal.UNKNOWN_STATE] spent the sign-in. */
  sealed interface Completion<out T> {

    /**
     * Not this sign-in's answer, so nothing in it was acted on. Answer it directly, never at an
     * address from [payload].
     *
     * @property payload `null` for [Refusal.UNKNOWN_STATE]; otherwise for the log only.
     */
    data class Refused<T>(val reason: Refusal, val payload: T?) : Completion<T>

    /**
     * This sign-in's answer, and a failure: the provider answered [providerError] (RFC 6749
     * §4.1.2.1, its own unchecked text), the code could not be exchanged or its token does not
     * belong to this sign-in ([cause]), or the callback carried neither code nor error (both `null`).
     */
    data class Failed<T>(
      val payload: T,
      val providerError: String? = null,
      val providerErrorDescription: String? = null,
      val cause: Exception? = null,
    ) : Completion<T>

    data class SignedIn<T>(val payload: T, val identity: OidcRelyingParty.VerifiedIdentity) : Completion<T>
  }

  /** @param prompt forwarded when it parses as an OIDC `prompt`, else dropped. */
  fun begin(relyingParty: OidcRelyingParty, payload: T, prompt: String? = null): Started {
    val state = parked.newKey()
    val started = relyingParty.beginAuthorization(prompt = prompt, state = state)
    val browserPin = Secrets.newSecret()
    parked.create(
      state,
      Parked(payload, relyingParty.redirectUri, started.codeVerifier, started.nonce, sha256Hex(browserPin)),
    )
    return Started(started.authorizationUrl, state, browserPin)
  }

  /** [complete] for a caller that already holds its relying party. */
  fun complete(relyingParty: OidcRelyingParty, callback: Callback): Completion<T> =
    checkNotNull(complete(relyingParty.redirectUri, callback) { relyingParty })

  /**
   * Completes the sign-in [Callback.state] names, at the [redirectUri] the callback arrived at.
   *
   * [relyingParty] is asked for only once a sign-in is parked there, so an unknown `state` costs no
   * discovery. It answers `null` where the provider cannot be reached; then so does this, and the
   * sign-in stays parked. A `state` this class could not have minted ([Secrets.isWellFormed]) is
   * not looked up.
   */
  fun complete(redirectUri: String, callback: Callback, relyingParty: () -> OidcRelyingParty?): Completion<T>? {
    val state = callback.state?.takeIf(Secrets::isWellFormed)
    if (state == null || parked.peek(state)?.redirectUri != redirectUri) return Completion.Refused(Refusal.UNKNOWN_STATE, null)
    val provider = relyingParty() ?: return null
    val pending = parked.consume(state)?.takeIf { it.redirectUri == provider.redirectUri }
      ?: return Completion.Refused(Refusal.UNKNOWN_STATE, null)
    val payload = pending.payload

    val pin = callback.presentedPin
    if (pin == null || !Secrets.matches(sha256Hex(pin), pending.pinSha256)) {
      return Completion.Refused(Refusal.OTHER_BROWSER, payload)
    }
    if (!provider.acceptsResponseIssuer(callback.iss)) return Completion.Refused(Refusal.WRONG_ISSUER, payload)

    if (callback.error != null) return Completion.Failed(payload, callback.error, callback.errorDescription)
    val code = callback.code?.trim()?.takeIf { it.isNotEmpty() } ?: return Completion.Failed(payload)
    return try {
      Completion.SignedIn(payload, provider.completeAuthorization(code, pending.codeVerifier, pending.nonce))
    } catch (e: Exception) {
      Completion.Failed(payload, cause = e)
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}

    @JvmField
    val DEFAULT_TTL: Duration = Duration.ofMinutes(15)

    /**
     * The `iss` to put in a [Callback], from every value the response carried. A repeated one is no
     * single answer (RFC 6749 §3.1), so it reads as a value no provider names, and is refused.
     */
    @JvmStatic
    fun responseIssuer(values: List<String>?): String? =
      if (values != null && values.size > 1) REPEATED_ISSUER else values?.firstOrNull()

    private const val REPEATED_ISSUER = ""

    private const val FIELD_PAYLOAD = "payload"
    private const val FIELD_REDIRECT_URI = "redirectUri"
    private const val FIELD_CODE_VERIFIER = "encryptedCodeVerifier"
    private const val FIELD_NONCE = "nonce"
    private const val FIELD_PIN_SHA256 = "browserPinSha256"
  }
}
