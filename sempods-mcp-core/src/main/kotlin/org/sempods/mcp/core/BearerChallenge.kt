package org.sempods.mcp.core

/**
 * The RFC 6750 `WWW-Authenticate: Bearer` challenge both MCP surfaces answer a 401 with.
 *
 * It points an MCP client at the RFC 9728 protected-resource metadata for the resource it just
 * failed to reach, so the client can discover the authorization server without out-of-band config.
 *
 * The parameters and their order are the protocol; *which* URLs go in is per surface. The pod
 * names the pod as the resource and its own name as the realm; the hosted service names the profile
 * path and a fixed service realm. Neither can be derived from the other — in particular the
 * metadata URL is not `"$resource/.well-known/…"` on the hosted side, where a named profile moves
 * the segment — so all three are parameters rather than one plus a rule.
 */
object BearerChallenge {

  /**
   * @param error RFC 6750 §3.1's code. `invalid_token` for a bearer that is missing or rejected,
   *   which is what most of this server sends; `insufficient_scope` where the bearer is good and
   *   does not cover the operation.
   */
  @JvmOverloads
  fun forResource(
    realm: String,
    resource: String,
    resourceMetadataUrl: String,
    error: String = INVALID_TOKEN,
  ): String =
    "Bearer realm=\"$realm\", " +
      "error=\"$error\", " +
      "resource=\"$resource\", " +
      "resource_metadata=\"$resourceMetadataUrl\""

  /** The bearer is missing, expired, revoked, or invalid for another reason. */
  const val INVALID_TOKEN = "invalid_token"

  /** The bearer is good, and the authority it carries does not cover this operation. */
  const val INSUFFICIENT_SCOPE = "insufficient_scope"
}
