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
 * **Neither grammar is loosened, and the reason is not tidiness.** Accepting a separator the
 * specification does not name guesses at what a malformed request meant, and a guess that happens
 * to match a real scope grants on the strength of it.
 *
 * An unrecognised *value* is a different question and is deliberately left open: neither
 * specification says to refuse one, so refusing would be this server's policy rather than
 * conformance. Unknown values survive parsing and are judged by whoever reads them.
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
