package org.sempods.auth.core

import com.nimbusds.jwt.JWTClaimsSet

/**
 * The two space-delimited request parameters OAuth and OIDC define, parsed once.
 *
 * Both grammars were implemented separately in every service that needed them — six `scope`
 * splitters and three `prompt` parsers across three modules, differing in whether they accepted a
 * tab, whether they returned a `List` or a `Set`, and whether they read `scp` as well as `scope`.
 * None of the differences was intended. All of them are gone; a seventh belongs here rather than
 * at its call site.
 */
object OAuthSyntax {

  /** `scope` per RFC 6749 §3.3. Tabs are accepted alongside spaces; empty entries drop out. */
  fun parseScope(raw: String?): Set<String> =
    raw?.split(' ', '\t')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

  fun formatScope(scopes: Collection<String>): String = scopes.joinToString(" ")

  /**
   * The scopes a **token** carries: the union of `scope` (space-delimited, RFC 6749) and `scp` (an
   * array). Both spellings are in use — the pod server mints `scope`, and tokens minted elsewhere in
   * this repository have used `scp` — so a reader that knows only one silently drops authority it
   * was handed.
   *
   * Distinct from [parseScope], which reads the request *parameter*: there the value is a single
   * string and there is nothing to union.
   *
   * Both claims are read through [stringClaimOrNull] / [stringListClaimOrNull], so a claim of the
   * wrong JSON type is the same answer as an absent one rather than a `ParseException` from a
   * public endpoint. That is deliberately narrower than mapping whatever the array held through
   * `toString()`: `{"scp": ["a", 1]}` now yields nothing at all. Fewer scopes is the safe direction
   * to be wrong in, and a non-string was never a scope.
   */
  fun scopeClaimValues(claims: JWTClaimsSet): Set<String> {
    val scopes = linkedSetOf<String>()
    claims.stringListClaimOrNull("scp")?.let { scopes.addAll(it.filter(String::isNotBlank).map(String::trim)) }
    scopes.addAll(parseScope(claims.stringClaimOrNull("scope")))
    return scopes
  }

  /**
   * `prompt` per OIDC Core 1.0 §3.1.2.1 — multi-valued, space-delimited, lower-cased.
   *
   * Unknown values are **kept**, not dropped: `none` is only meaningful in combination with what
   * else was asked for, so a caller has to see the whole set to reject the illegal combinations.
   * Deciding which values may travel on to an upstream provider is [OidcPrompt]'s job.
   */
  fun parsePrompt(raw: String?): Set<String> =
    raw?.lowercase()?.split(' ', '\t')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

  /** `prompt` values that demand a fresh authentication rather than accepting a live session. */
  val FORCE_REAUTH_PROMPTS: Set<String> = setOf("login", "select_account")

  /** `none` forbids any interaction, so it cannot be combined with a value that requires some. */
  fun isContradictoryPrompt(prompt: Set<String>): Boolean =
    PROMPT_NONE in prompt && prompt.size > 1

  const val PROMPT_NONE = "none"
}
