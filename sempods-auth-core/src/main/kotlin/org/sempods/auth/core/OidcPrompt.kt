package org.sempods.auth.core

/**
 * The `prompt` values this bridge forwards to an upstream provider (OIDC Core 1.0 §3.1.2.1).
 *
 * A closed set rather than a pass-through string: `/authorize` is a public endpoint, so whatever
 * arrives there would otherwise end up in the provider's authorize URL. Anything unrecognised is
 * dropped by [parse] instead of forwarded.
 */
enum class OidcPrompt(val value: String) {

  /** Re-authenticate the user even if the provider has a live session. */
  LOGIN("login"),

  /** Offer the account chooser; implies [LOGIN] at every provider that supports it. */
  SELECT_ACCOUNT("select_account"),

  ;

  companion object {

    /** `null` for absent, blank or unrecognised input — an unknown prompt is not forwarded. */
    fun parse(raw: String?): OidcPrompt? {
      val trimmed = raw?.trim()?.lowercase() ?: return null
      return entries.firstOrNull { it.value == trimmed }
    }
  }
}
