package org.sempods.admin

import org.sempods.commons.config.Env
import org.sempods.commons.net.BearerAuth
import java.security.MessageDigest

/**
 * Credential-checking [AdminAuthorizer]: a per-client shared secret presented as
 * `Authorization: Bearer <secret>`. The authority every deployment gets — the admin surface is
 * reachable over HTTP in all of them.
 *
 * Behaviour:
 *
 * - **Fail closed.** With no configured credentials every call is
 *   [AdminAuthorization.NotConfigured], which consumers turn into 503. There is no "allow while
 *   unconfigured" mode.
 * - Header parsing goes through [BearerAuth], so the scheme name matches case-insensitively as
 *   RFC 7235 requires while the secret itself is still compared byte for byte.
 * - The secret itself identifies the caller: the configured client id whose secret matches is
 *   returned in [AdminAuthorization.Authorized]. Client ids are therefore not transmitted and
 *   cannot be probed.
 * - Comparison is constant-time ([MessageDigest.isEqual]) and runs against **every** configured
 *   entry without an early exit, so neither the matching client nor the position of a near-miss
 *   is observable through response timing.
 *
 * Secrets are opaque strings; the pod service-client generator's `sc_`-prefixed 32-byte random
 * values ([org.sempods.pods.oauth.serviceclients.PodServiceClientStore]) are a suitable shape,
 * but nothing here depends on it.
 */
class StaticCredentialAdminAuthorizer(
  secretsByClientId: Map<String, String>,
) : AdminAuthorizer {

  private val credentials: List<Pair<String, ByteArray>> = secretsByClientId
    .map { (clientId, secret) -> clientId to secret.toByteArray(Charsets.UTF_8) }

  override fun authorize(authorizationHeader: String?): AdminAuthorization {

    if (credentials.isEmpty()) {
      return AdminAuthorization.NotConfigured
    }

    val presented = BearerAuth.parse(authorizationHeader) ?: return AdminAuthorization.Denied
    val presentedBytes = presented.toByteArray(Charsets.UTF_8)

    // No early exit: every configured credential is compared even after a match, so the work done
    // is independent of which entry matches (or whether any does).
    var matchedClientId: String? = null
    for ((clientId, secretBytes) in credentials) {
      if (MessageDigest.isEqual(presentedBytes, secretBytes) && matchedClientId == null) {
        matchedClientId = clientId
      }
    }

    return matchedClientId?.let { AdminAuthorization.Authorized(it) } ?: AdminAuthorization.Denied
  }

  companion object {

    /** Named rather than inlined because the rejection message below repeats it. */
    private const val ENV_VARIABLE = "SEMPODS_ADMIN_CLIENTS"

    /**
     * Parses the `SEMPODS_ADMIN_CLIENTS` configuration value: a comma-separated list of
     * `clientId:secret` pairs (`console:sc_AbC…,ops:sc_XyZ…`), mirroring the comma-separated
     * `SEMPODS_AUTH_ISSUERS`. A blank or absent value yields an empty map — i.e. an authorizer
     * that fails closed.
     *
     * A malformed entry throws [IllegalStateException] at boot instead of being skipped: a typo
     * that silently drops one operator's credential is worse than a server that refuses to start.
     * Secrets consequently must not contain `,`; they may contain `:` (only the first one splits).
     *
     * Two clients sharing a secret are rejected on the same grounds, and it is the more dangerous
     * typo of the two. [Env.pairsOf] already refuses a repeated client id; a repeated *secret* is
     * what it cannot see, and it quietly undoes the reason the entries are per client. The caller
     * a request is attributed to becomes whichever entry happens to be first — which is already
     * at odds with the secret identifying the caller, as stated above — and, the part that
     * matters, rotating that client's secret no longer revokes it, because the old value still
     * authorizes through the other entry. Rotation that does not revoke is worse than no
     * rotation, since it is believed.
     */
    fun parseClients(raw: String?): Map<String, String> {
      val clients = Env.pairsOf(raw, label = ENV_VARIABLE)
      check(clients.values.toSet().size == clients.size) {
        // The ids, never the secret they share: this message reaches a log.
        "two clients in $ENV_VARIABLE share a secret, so neither can be rotated on its own " +
            "(client ids: ${clients.keys.sorted().joinToString()})"
      }
      return clients
    }
  }
}
