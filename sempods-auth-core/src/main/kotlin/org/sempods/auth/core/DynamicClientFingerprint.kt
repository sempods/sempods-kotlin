package org.sempods.auth.core

import java.security.MessageDigest

/**
 * De-duplication key for RFC 7591 Dynamic Client Registrations.
 *
 * Clients with no persistent client-state (Claude Code/Desktop and similar) re-register on every
 * reconnect. Each call would otherwise land a fresh `dyn:<random>` that the user has never
 * consented to, orphaning the previous consent. The registrations differ only in the ephemeral
 * loopback port of their redirect URIs, while `clientName`, `userAgent` and the URI *shape* stay
 * stable — so a digest over the stable parts keeps consent anchored to one `client_id`.
 *
 * Inputs: `clientName`, `userAgent`, a [realm] (the variable path segment the client registered
 * against), and the redirect URIs canonicalized by [RedirectUri.canonicalize].
 *
 * The digest is always scoped against a tenant by the caller and never compared across tenants.
 */
object DynamicClientFingerprint {

  fun compute(
    clientName: String?,
    userAgent: String?,
    realm: String?,
    redirectUris: Collection<String>,
  ): String =
    MessageDigest.getInstance("SHA-256")
      .digest(canonicalSource(clientName, userAgent, realm, redirectUris).toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }

  /**
   * The pre-digest form. Exposed so a caller can log what it hashed; only the digest is stored.
   *
   * The realm field is labelled `r`. The two copies this replaced used different letters — `m`
   * for the pod's MCP path, `p` for the hosted service's `profile` — so unifying it changed every
   * stored digest, and every client registered before that change re-registered once and lost the
   * consent attached to its old `client_id`. That is a real cost and it was paid on purpose:
   * every deployment is still a proof of concept, and the alternative was to carry the
   * inconsistency into the public repository forever.
   *
   * There is no legacy-digest lookup, so touching [RedirectUri.canonicalize] is a decision about
   * consent: every registration whose canonical form moves re-registers once under a new
   * `client_id`.
   *
   * Only the hosted service fills the realm today. The pod passes `null`: it has one MCP surface,
   * so there is nothing to fork registrations by.
   */
  fun canonicalSource(
    clientName: String?,
    userAgent: String?,
    realm: String?,
    redirectUris: Collection<String>,
  ): String {
    val canonicalUris = redirectUris
      .map { RedirectUri.canonicalize(it.trim()) }
      .sorted()
      .joinToString(",")
    return "cn=${clientName?.trim().orEmpty()}|ua=${userAgent?.trim().orEmpty()}|" +
        "r=${realm?.trim().orEmpty()}|u=$canonicalUris"
  }
}
