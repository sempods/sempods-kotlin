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
 *
 * **The grammars are the specifications', not a lenient reading of them.** Both parsers used to
 * split on tabs as well as spaces and the `prompt` one lower-cased what it read, which accepted
 * requests the specifications do not define: RFC 6749 §3.3 separates scope tokens with a single
 * space and does not admit a tab *inside* one either, and OIDC Core §3.1.2.1 calls `prompt` a
 * case-sensitive list. Tolerating more than that guesses at what a malformed request meant, and a
 * guess that happens to match a real scope grants on the strength of it. `OAuthSyntaxTest` holds
 * both parsers against the OAuth SDK's own so a future divergence is a red test rather than a
 * discovery.
 *
 * What is *not* tightened here is an unrecognised value. Neither specification says to refuse one,
 * and refusing would be a policy of this server rather than conformance — the roadmap's strictness
 * decision owns that question. Unknown values therefore survive parsing and are judged by whoever
 * reads them.
 */
object OAuthSyntax {

  /**
   * `scope` per RFC 6749 §3.3: values separated by spaces. Repeated spaces collapse — a formatting
   * slip cannot be told from the single separator the grammar names — and nothing else is treated
   * as a separator, so a tab travels inside the value it sits in and is refused there as the
   * unknown scope it makes.
   */
  fun parseScope(raw: String?): Set<String> =
    raw?.split(' ')?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

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
   * `prompt` per OIDC Core 1.0 §3.1.2.1 — multi-valued, space-delimited, **case sensitive**, which
   * is why nothing is folded here: `NONE` is not the `none` the specification defines, and treating
   * it as one would apply the strictest rule in the parameter to a request that did not ask for it.
   *
   * Unknown values are **kept**, not dropped: `none` is only meaningful in combination with what
   * else was asked for, so a caller has to see the whole set to reject the illegal combinations.
   * Deciding which values may travel on to an upstream provider is [OidcPrompt]'s job.
   */
  fun parsePrompt(raw: String?): Set<String> =
    raw?.split(' ')?.filter(String::isNotEmpty)?.toSet() ?: emptySet()

  /** `prompt` values that demand a fresh authentication rather than accepting a live session. */
  val FORCE_REAUTH_PROMPTS: Set<String> = setOf("login", "select_account")

  /** `none` forbids any interaction, so it cannot be combined with a value that requires some. */
  fun isContradictoryPrompt(prompt: Set<String>): Boolean =
    PROMPT_NONE in prompt && prompt.size > 1

  const val PROMPT_NONE = "none"
}
