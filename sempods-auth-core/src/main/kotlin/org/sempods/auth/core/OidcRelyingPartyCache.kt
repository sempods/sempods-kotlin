package org.sempods.auth.core

import java.util.concurrent.ConcurrentHashMap

/**
 * One relying party per redirect address, against one provider discovered once.
 *
 * Every service that signs people in needs more than one: the address the provider sends the
 * browser back to differs per flow — a pod, a callback path — while the provider behind it does
 * not. Both halves are worth keeping. The **metadata** because discovery is a network round trip
 * that says the same thing every time, and the **relying party** because it holds the SDK's
 * self-refreshing view of the provider's signing keys; a fresh one per sign-in turns that cache
 * into a JWKS fetch per sign-in.
 *
 * **The key is the redirect URI**, which is what the callers actually differ in. A cache keyed by
 * anything else — a pod name, a callback path — is that same key with a service-specific spelling,
 * and two spellings is how the two copies of this class came to differ in the first place: one
 * shared the discovered metadata across its entries and the other re-discovered per entry.
 *
 * Discovery is lazy rather than done at construction: a network call at wiring time means a service
 * that refuses to boot because the identity provider is momentarily unreachable, which is worse
 * than one whose logins fail while it is. The first sign-in after it recovers succeeds.
 *
 * @param issuer non-null on purpose. Whether a deployment configured one, and what to say when it
 *   did not, is the calling service's answer to give — it knows which setting is missing.
 */
class OidcRelyingPartyCache(
  private val issuer: String,
  private val clientId: String,
  private val transport: HttpTransport,
) {

  private val byRedirectUri = ConcurrentHashMap<String, OidcRelyingParty>()

  @Volatile
  private var metadata: OidcProviderMetadata? = null

  /**
   * @throws IllegalStateException when discovery fails, or the provider advertises an issuer other
   *   than the configured one. A caller answers that by telling the user sign-in is unavailable,
   *   not by sending them to a login page that cannot work.
   */
  fun forRedirectUri(redirectUri: String): OidcRelyingParty {
    byRedirectUri[redirectUri]?.let { return it }

    // Built outside the map, never in `computeIfAbsent`: the first call through here performs a
    // network round trip, and doing that under the map's bin lock would block every other key that
    // hashes to it. The cost is that two callers racing on a cold cache may both build one —
    // `putIfAbsent` then keeps the winner, so the loser's instance is discarded rather than
    // replacing one another caller is already holding a warm key cache in.
    val known = metadata
    val relyingParty = if (known != null) {
      OidcRelyingParty(known, clientId, redirectUri, transport)
    } else {
      OidcRelyingParty.discover(issuer, clientId, redirectUri, transport).also { metadata = it.metadata }
    }
    return byRedirectUri.putIfAbsent(redirectUri, relyingParty) ?: relyingParty
  }
}
