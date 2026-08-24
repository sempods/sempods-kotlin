package org.sempods.mcp

import org.sempods.commons.config.Env

/**
 * Configuration for the hosted MCP service.
 *
 * - [mcpBaseUrl] is the service's own externally-visible base URL. It is the OAuth
 *   **resource identifier** and **authorization-server issuer** the AI client discovers
 *   (RFC 9728 / RFC 8414), so it must match what clients reach the service at.
 * - [authIssuers] is the set of trusted sempods-auth issuers (id.sempods.org) whose WebID
 *   JWTs establish the stable `user`. Empty disables OIDC verification (local dev only).
 * - [allowLocalPods] relaxes the SSRF defense (URL-string guard + connect-time DNS vetting) to
 *   permit http/loopback/private pods — the deploy-time strict/relaxed split for local dev and
 *   self-host. See `PodUrlPolicy` and `:sempods-client`'s `SempodsOutboundGuard` (M6.2).
 * - [podRateLimitPerMinute] is the per-pod-host request budget on the hardened fetch path;
 *   `0` disables the limiter.
 * - [userRateLimitPerMinute] is the per-`(user, profile)` `tools/call` budget on the MCP
 *   endpoint (M6.4); `0` disables the limiter. Like the pod limiter it is in-memory per
 *   replica, so the effective budget scales with the replica count.
 * - [auditRetentionDays] bounds the `auditLog` trail (M6.4): each row's TTL anchor is
 *   computed at write time, so a change affects only new rows.
 * - [podTokenRefreshWindowSeconds] is how long before expiry the background loop refreshes a
 *   pod access token, and [podTokenWarmIdleSeconds] is how long after a pod was last used it is
 *   still worth doing that at all. Past the threshold the trade turns: warm-keeping spends a
 *   rotation per token lifetime to save the one an on-demand refresh would cost, once.
 * - [podTokenFamilyPreserveSeconds] is the cadence on which every connection is rotated whether or
 *   not anyone uses it — what keeps the pod's refresh-token family and the service's DCR
 *   registration there inside their ninety-day deadline. The pod does not advertise that TTL (RFC
 *   6749 has no field for it), so the default is a conservative guess against a value the pod owns.
 */
data class SempodsMcpConfig(
  val port: Int,
  val mongoUrl: String,
  val mongoDbName: String,
  val mcpBaseUrl: String,
  val authIssuers: List<String>,
  val sessionCookieName: String = "mcp_session",
  val allowLocalPods: Boolean = false,
  val podRateLimitPerMinute: Int = 0,
  val userRateLimitPerMinute: Int = 0,
  val auditRetentionDays: Long = 90,
  val podTokenRefreshWindowSeconds: Long = 300,
  val podTokenWarmIdleSeconds: Long = 3600,
  val podTokenFamilyPreserveSeconds: Long = 30 * 24 * 60 * 60,
) {
  /** True for a public/https deployment; relaxes nothing yet but gates secure-cookie flags. */
  val isSecure: Boolean get() = mcpBaseUrl.startsWith("https://")

  companion object {
    fun fromEnv(): SempodsMcpConfig {
      val mcpBaseUrl = (Env.get("MCP_BASE_URL") ?: "https://mcp.sempods.org").trimEnd('/')
      // Default: permit local/loopback pods only on a non-https (local) deployment.
      val allowLocalPods = Env.get("ALLOW_LOCAL_PODS")?.toBooleanStrictOrNull() ?: !mcpBaseUrl.startsWith("https://")
      return SempodsMcpConfig(
        port = Env.get("PORT")?.toIntOrNull() ?: 8092,
        mongoUrl = Env.get("MONGODB_URL") ?: "mongodb://localhost:27018",
        mongoDbName = Env.get("MONGODB_DB_NAME") ?: "sempods-mcp",
        mcpBaseUrl = mcpBaseUrl,
        // Absent means the production issuer; set-but-empty means trust none, which is the
        // documented local-development mode. The default therefore applies to the raw value,
        // not to the parsed list.
        authIssuers = Env.list("SEMPODS_AUTH_ISSUERS", ',', ' ', default = "https://id.sempods.org")
          .map { it.trimEnd('/') },
        allowLocalPods = allowLocalPods,
        // Default: a generous budget on a strict (public) deployment, off for local/self-host.
        podRateLimitPerMinute = Env.get("POD_RATE_LIMIT_PER_MINUTE")?.toIntOrNull() ?: if (allowLocalPods) 0 else 120,
        userRateLimitPerMinute = Env.get("USER_RATE_LIMIT_PER_MINUTE")?.toIntOrNull() ?: if (allowLocalPods) 0 else 120,
        auditRetentionDays = Env.get("AUDIT_RETENTION_DAYS")?.toLongOrNull() ?: 90,
        podTokenRefreshWindowSeconds = Env.get("POD_TOKEN_REFRESH_WINDOW_SECONDS")?.toLongOrNull() ?: 300,
        // 0 disables the warm tier — every first call after an idle period then rotates on demand.
        podTokenWarmIdleSeconds = Env.get("POD_TOKEN_WARM_IDLE_SECONDS")?.toLongOrNull() ?: 3600,
        // 30 days: comfortably inside a ninety-day family, with room for a missed run, a restart or
        // a pod that is briefly unreachable. 0 disables the tier, which is not a supported operating
        // mode — a connection nobody uses then lapses on the pod's own clock.
        podTokenFamilyPreserveSeconds = Env.get("POD_TOKEN_FAMILY_PRESERVE_SECONDS")?.toLongOrNull() ?: (30L * 24 * 60 * 60),
      )
    }
  }
}
