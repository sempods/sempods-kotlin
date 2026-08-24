package org.sempods.mcp.persist

/**
 * The profile path segment on the service's own URL namespace (M5). A named profile is reached
 * at `mcp.sempods.org/<profile>` (suffix-free); the default profile lives at the service root
 * `mcp.sempods.org` and is **implicit** (no segment).
 *
 * Because the profile segment sits directly on the root — there is no `/mcp` (or other) sub-tree
 * insulating it — every literal route mounted at the root is a name a profile must **not** take.
 * Ktor resolves literal segments ahead of `/{profile}`, so `/authorize`, `/token`, `/_system`,
 * `/.well-known`, … always route to their real handlers; this [RESERVED] guard is the second line
 * of defence that stops anyone *creating* a profile that would shadow one of them.
 */
object ProfilePath {

  /**
   * Top-level path segments that are real service routes and therefore can never be a profile
   * name. `mcp` is included even though M5 drops the old `/mcp` endpoint, to avoid resurrecting
   * its former meaning by way of a profile. `default` is reserved so the sentinel never becomes a
   * routable second URL for the root profile (the default profile lives at the service root only).
   */
  // TODO: this set duplicates knowledge of the literal root routes (McpEndpoint, AuthEndpoint,
  //  OAuthMetadataEndpoint, health). Adding a new root route without updating RESERVED would let a
  //  user create a profile that Ktor shadows with the literal route. Consider deriving reserved
  //  names from the registered route table so the coupling is enforced, not hand-maintained.
  val RESERVED: Set<String> = setOf(
    "_system",
    ".well-known",
    "jwks.json",
    "register",
    "authorize",
    "token",
    "oidc",
    "healthz",
    "favicon.ico",
    "mcp",
    PodKey.DEFAULT_PROFILE,
  )

  private val NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

  /**
   * A valid named profile: path-safe lowercase and not one of the [RESERVED] route segments.
   */
  fun isValidName(name: String): Boolean =
    NAME_REGEX.matches(name) && name !in RESERVED

  /**
   * Resolve the profile a request addresses from its `{profile}` path parameter:
   * - `null`/blank (a root route) → the implicit [PodKey.DEFAULT_PROFILE].
   * - a valid named profile → that name.
   * - anything else (reserved or malformed) → `null`, signalling the caller to answer 404.
   */
  fun normalize(rawSegment: String?): String? {
    val segment = rawSegment?.trim()
    if (segment.isNullOrEmpty()) return PodKey.DEFAULT_PROFILE
    return if (isValidName(segment)) segment else null
  }

  /**
   * The service URL for a profile — the single source of truth shared by token issuance, token
   * verification, OAuth discovery metadata, the MCP challenge, and the UI: the service root for the
   * default (or blank) profile, `<serviceBase>/<profile>` for a named one. Blank is folded to the
   * root so a stray empty profile claim never yields a trailing-slash URL that would fail the
   * issuer↔metadata equality check.
   */
  fun baseUrlFor(serviceBase: String, profile: String): String =
    if (profile.isBlank() || profile == PodKey.DEFAULT_PROFILE) serviceBase else "$serviceBase/$profile"
}
