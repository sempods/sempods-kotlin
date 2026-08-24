package org.sempods.auth.core

import java.net.URI

/**
 * Whether a client may receive a response at a given address.
 *
 * This is the check that decides who can be handed a token, and the services differ in how they
 * answer it — the pod accepts both `did:web:` static clients and `dyn:` registrations, the
 * identity service only the former. The interface is what lets the shared authorize/token logic
 * ask the question without knowing which.
 */
fun interface ClientRedirectPolicy {

  fun permits(clientId: String, redirectUri: String): Boolean
}

/**
 * `did:web:` static clients: the address must sit on the origin the identifier names.
 *
 * Nothing is dereferenced. `did:web:example.org` may only be answered at `https://example.org/…`,
 * which is the entire security property — a client can prove nothing about itself, but it can also
 * never receive a response anywhere except its own origin. A client-metadata document that had to
 * be fetched would add SSRF surface, a cache, and a third party in the login path, and would not
 * make the answer any stronger.
 *
 * @param allowLoopback development only. A loopback address in production would mean an
 *   authorization code can be intercepted by anything running on the user's machine, so this is
 *   wired from the environment and never defaulted on.
 */
class DidWebRedirectPolicy(private val allowLoopback: Boolean = false) : ClientRedirectPolicy {

  override fun permits(clientId: String, redirectUri: String): Boolean {
    if (!RedirectUri.isValid(redirectUri)) return false
    val target = DidWeb.targetOf(clientId) ?: return false
    val uri = runCatching { URI(redirectUri) }.getOrNull() ?: return false

    // A did:web with path segments narrows the client to that subtree, so the address has to be
    // inside it — otherwise two services sharing a host could answer for each other.
    if (target.covers(uri)) return true

    return allowLoopback && RedirectUri.isLoopback(uri.host?.trim()?.lowercase())
  }
}
