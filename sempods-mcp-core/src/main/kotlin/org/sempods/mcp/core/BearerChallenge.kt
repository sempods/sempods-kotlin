package org.sempods.mcp.core

/**
 * The RFC 6750 `WWW-Authenticate: Bearer` challenge both MCP surfaces answer a 401 with.
 *
 * It points an MCP client at the RFC 9728 protected-resource metadata for the resource it just
 * failed to reach, so the client can discover the authorization server without out-of-band config.
 *
 * The four parameters and their order are the protocol; *which* URLs go in is per surface. The pod
 * names the pod as the resource and its own name as the realm; the hosted service names the profile
 * path and a fixed service realm. Neither can be derived from the other — in particular the
 * metadata URL is not `"$resource/.well-known/…"` on the hosted side, where a named profile moves
 * the segment — so all three are parameters rather than one plus a rule.
 */
object BearerChallenge {

  /**
   * `error` is always `invalid_token`: that is what both surfaces send today, for a missing bearer
   * as much as for a rejected one. A parameter with one caller and one value would be speculation;
   * give it one when something needs `insufficient_scope`.
   */
  fun forResource(realm: String, resource: String, resourceMetadataUrl: String): String =
    "Bearer realm=\"$realm\", " +
      "error=\"invalid_token\", " +
      "resource=\"$resource\", " +
      "resource_metadata=\"$resourceMetadataUrl\""
}
