package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet
import java.net.URI
import java.net.URISyntaxException

/**
 * The ID Token claim that names a person's equivalent identities (`SPS-OIDC-005`): WebIDs that
 * identify the same person as `sub`.
 *
 * ```json
 * {
 *   "sub": "https://id.sempods.org/oidc/7f3a",
 *   "https://schema.sempods.org/claims/equivalent-identities": ["https://id.sempods.org/e/91c2"]
 * }
 * ```
 *
 * The registered OIDC claim `also_known_as` is a human pseudonym and names no identity. Nothing in
 * this module reads it.
 */
object EquivalentIdentities {

  /** The claim name: a JWT claim identifier, with no RDF property behind it. */
  const val CLAIM = "https://schema.sempods.org/claims/equivalent-identities"

  private val WEB_SCHEMES = setOf("http", "https")

  /**
   * Whether [value] may be a member of the claim: an HTTP or HTTPS URI with a host, in RFC 3986 §3
   * syntax. A fragment is allowed.
   *
   * | Value | Member |
   * |---|---|
   * | `https://id.example/alice#me`, `http://id.example/e/91c2` | yes |
   * | `""`, `/alice`, `https:alice`, `urn:sempods:e:91c2`, `did:web:id.example` | no |
   */
  fun isWebIdUri(value: String): Boolean {
    // `java.net.URI` parses the older RFC 2396 grammar and also admits non-ASCII characters.
    // RFC 3986 allows no character outside printable ASCII, so those are refused first.
    if (value.any { it !in '!'..'~' }) return false
    val uri = try {
      URI(value)
    } catch (_: URISyntaxException) {
      return false
    }
    return uri.scheme?.lowercase() in WEB_SCHEMES && !uri.host.isNullOrEmpty()
  }

  /**
   * The identities [claims] asserts as equivalent to its `sub`, as a set (`SPS-OIDC-017`). Read it
   * from a validated ID Token only.
   *
   * | Claim | Result |
   * |---|---|
   * | absent, or `[]` | no identities |
   * | an array of WebID URIs ([isWebIdUri]) | those URIs; a duplicate or `sub` itself adds nothing |
   * | `null`, a string, a number, an object, or an array with any other member | refused |
   *
   * @throws IllegalStateException when the claim is present in any other shape. That refuses the
   *   whole identity assertion (`SPS-OIDC-016`), so an array with one bad member is refused as a
   *   whole; keeping its valid members would accept a set the issuer never stated.
   */
  fun read(claims: JWTClaimsSet): Set<String> {
    if (CLAIM !in claims.claims) return emptySet()
    val members = claims.getClaim(CLAIM) as? List<*> ?: error("the claim $CLAIM is not an array")
    members.forEachIndexed { index, member ->
      check(member is String && isWebIdUri(member)) { "entry $index of the claim $CLAIM is not a WebID URI" }
    }
    return members.filterIsInstance<String>().toSet() - setOfNotNull(claims.subject)
  }
}
