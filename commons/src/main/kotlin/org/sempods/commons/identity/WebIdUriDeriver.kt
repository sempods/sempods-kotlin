package org.sempods.commons.identity

import java.security.MessageDigest
import java.util.Locale

/**
 * Derives WebID URIs from email addresses using a configurable base URL.
 *
 * The SHA-256 formula is open and deterministic — any module can derive the same URI
 * from an email without contacting sempods-auth. The formula used here is byte-identical
 * to the one in sempods-auth LoginService:
 *   digest = SHA-256(email.trim().lowercase(Locale.ROOT).toByteArray(UTF-8))
 *   uri    = "$idBaseUrl/e/${digest.hex()}"
 *
 * Locale.ROOT is used for lowercasing to ensure identical hashes across JVM deployments
 * regardless of the system locale (e.g. Turkish locale maps 'I' → 'ı', not 'i').
 *
 * Minting is email-only here — the `oidc/` namespace is derived from `iss + ":" + sub`, which
 * only sempods-auth sees. Recognising both namespaces is a different matter: [derivableAliases]
 * covers them, because grants can be recorded under either form.
 */
class WebIdUriDeriver(private val idBaseUrl: String) {

  /** Email-based WebID: `{idBaseUrl}/e/<sha256hex>` */
  fun deriveFromEmail(email: String): String {
    val hash = sha256Hex(email.trim().lowercase(Locale.ROOT))
    return "$idBaseUrl/e/$hash"
  }

  /** Returns true if [webId] matches the email-based pattern for this instance's base URL. */
  fun isEmailBasedWebId(webId: String): Boolean {
    return webId.startsWith("$idBaseUrl/e/") && webId.length == idBaseUrl.length + 3 + 64
        && webId.substring(idBaseUrl.length + 3).all { it in '0'..'9' || it in 'a'..'f' }
  }

  /** Throws [IllegalArgumentException] if [webId] is not a valid email-based WebID for this instance. */
  fun requireEmailBasedWebId(webId: String) {
    require(isEmailBasedWebId(webId)) {
      "Pod owner must be an email-based WebID ($idBaseUrl/e/<sha256>), got: '$webId'"
    }
  }

  /**
   * Every URI that provably denotes the same subject as [webId] **without contacting
   * sempods-auth** — always including [webId] itself.
   *
   * sempods-auth mints identity URIs in two namespaces, and in each one the dereferenceable WebID
   * and its URN twin carry the *same* hash (`sempods-auth/docs/identity-service.md`, "Token
   * format"):
   *
   * ```
   * {idBaseUrl}/e/<hash>     ↔  urn:sempods:e:<hash>       hash = sha256(normalize(email))
   * {idBaseUrl}/oidc/<hash>  ↔  urn:sempods:oidc:<hash>    hash = sha256(normalize(iss + ":" + sub))
   * ```
   *
   * so either form is recoverable from the other by carrying the hash across. Both namespaces
   * matter for revocation: a grant may be recorded under the URN (the Layer-0 form a pod can
   * compute without sempods-auth) while the revocation names the canonical WebID, or the other
   * way round.
   *
   * Nothing beyond these pairs is derivable. Arbitrary `also_known_as` links — including the
   * bridge between a person's `e:` and `oidc:` identities after an identity merge — live in the
   * sempods-auth WebID profile (separate service, separate database). `urn:sempods:anon:<uuid>`
   * has no twin at all. Callers that must cover the non-derivable links have to carry the
   * subject's URI set with them from the point where an identity JWT was verified — see
   * `PodGrantDbo.subjectUris`.
   */
  fun derivableAliases(webId: String): Set<String> {
    val normalized = webId.trim()
    if (normalized.isEmpty()) return emptySet()
    for (namespace in IDENTITY_NAMESPACES) {
      val hash = normalized.removePrefixOrNull("$idBaseUrl/$namespace/")
        ?: normalized.removePrefixOrNull("$URN_PREFIX$namespace:")
        ?: continue
      if (!isSha256Hex(hash)) return setOf(normalized)
      return setOf(normalized, "$idBaseUrl/$namespace/$hash", "$URN_PREFIX$namespace:$hash")
    }
    return setOf(normalized)
  }

  private fun String.removePrefixOrNull(prefix: String): String? =
    if (startsWith(prefix)) substring(prefix.length) else null

  companion object {
    /** Common prefix of every sempods identity URN. */
    const val URN_PREFIX = "urn:sempods:"

    /** Prefix of the URN form of an email-based WebID (see [deriveUrnFromEmail]). */
    const val URN_EMAIL_PREFIX = "${URN_PREFIX}e:"

    /** Prefix of the URN form of an OIDC-based WebID (minted by sempods-auth for email-less logins). */
    const val URN_OIDC_PREFIX = "${URN_PREFIX}oidc:"

    /**
     * Path/URN segments of the identity namespaces whose two URI forms share one hash. `anon` is
     * deliberately absent: a synthetic anonymous subject carries a UUID, not a derivable hash.
     */
    private val IDENTITY_NAMESPACES = listOf("e", "oidc")

    fun sha256Hex(input: String): String {
      val digest = MessageDigest.getInstance("SHA-256")
      return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    }

    private fun isSha256Hex(value: String): Boolean =
      value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun deriveUrnFromEmail(email: String): String {
      val hash = sha256Hex(email.trim().lowercase(Locale.ROOT))
      return "$URN_EMAIL_PREFIX$hash"
    }
  }
}
