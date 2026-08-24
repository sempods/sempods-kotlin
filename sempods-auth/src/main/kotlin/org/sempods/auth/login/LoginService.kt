package org.sempods.auth.login

import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.auth.SempodsAuthConfig
import org.sempods.auth.oidc.OidcClaims
import org.sempods.auth.persist.WebIdNamespace
import org.sempods.auth.persist.WebIdProfile
import org.sempods.auth.persist.WebIdProfileDao
import java.util.Date

/**
 * Turns what an upstream provider said into the person this service knows.
 *
 * 1. Derive the WebID URI and its URN twin from the OIDC claims (email namespace, or the OIDC
 *    namespace when the provider gave no verified address)
 * 2. Look up or create the WebID profile
 *
 * It used to mint a token here too — the `/login` endpoint's `aud`-less identity JWT, produced on
 * every callback and discarded on the `/authorize` one. Tokens are now issued where the request
 * that asked for them is answered (`api/provider/OpenIdProviderEndpoint.kt`), so this says who
 * someone is and nothing more.
 */
class LoginService(
  private val config: SempodsAuthConfig,
  private val webIdProfileDao: WebIdProfileDao,
) {

  private val webIdDeriver = WebIdUriDeriver(config.idBaseUrl)

  /** @return the canonical WebID URI for [claims]. */
  fun processLogin(claims: OidcClaims): String {
    val (webIdUri, urnUri) = deriveUris(claims)

    val profile = webIdProfileDao.findByUri(webIdUri)
      ?.fillInMissingDisplayName(claims)
      ?: WebIdProfile(
        id = webIdUri,
        namespace = if ("/e/" in webIdUri) WebIdNamespace.EMAIL else WebIdNamespace.OIDC,
        displayName = claims.displayName ?: "",
        createdAt = Date(),
      ).also { webIdProfileDao.upsert(it) }

    return webIdUri
  }

  /**
   * Every identity URI equivalent to [webIdUri] — what a pod matches a grant against.
   *
   * Two halves, and leaving out either one breaks something specific:
   *
   * - The **URN twin** is derivable, because a WebID and its URN carry the same hash across the
   *   namespace (`WebIdUriDeriver.derivableAliases`). It is what a pod without a sempods-auth
   *   configuration can compute on its own, and therefore what an invitation sent to an email
   *   address resolves to *before that person has ever logged in*. Omit it and grant-before-login
   *   silently does nothing — the flow `identity-service.md` §"Email → Grant Flow" describes.
   * - The **linked identities** are what an identity merge recorded, and nothing can derive them.
   *
   * One method rather than one per caller: this is a property of the person, not of whichever
   * flow is asking, and the two flows disagreeing about it is exactly how the URN went missing
   * from the provider flow once it stopped reusing the legacy token.
   */
  fun aliasesFor(webIdUri: String, linkedIdentities: List<String>? = null): List<String> {
    val linked = linkedIdentities ?: webIdProfileDao.findByUri(webIdUri)?.linkedIdentities.orEmpty()
    // `derivableAliases` includes the WebID itself; `sub` already carries it.
    val derivable = webIdDeriver.derivableAliases(webIdUri) - webIdUri
    return (derivable + linked).distinct()
  }

  /**
   * Fills a blank display name from the current login, and only a blank one.
   *
   * Apple sends the user's name exactly once — on the first authorization, as a form field — and
   * never again. If that login lands on a profile created earlier by a grant or by a provider that
   * had no name to give, the one chance to record it would otherwise pass unused.
   *
   * Deliberately not an overwrite: a name already on the profile is the more considered value, and
   * a later login from a provider with a different spelling should not quietly replace it.
   *
   * TODO: with profile management (see `docs/identity-service.md`, "Open Questions") this becomes
   *   the user's decision rather than a first-writer-wins rule.
   */
  private fun WebIdProfile.fillInMissingDisplayName(claims: OidcClaims): WebIdProfile {
    val incoming = claims.displayName?.takeIf { it.isNotBlank() } ?: return this
    if (displayName.isNotBlank()) return this
    return copy(displayName = incoming).also { webIdProfileDao.upsert(it) }
  }

  /**
   * Derives the WebID URI and the corresponding urn:sempods URI.
   *
   * Email present  → EMAIL namespace: id.sempods.org/e/<sha256(normalize(email))>
   * No email       → OIDC namespace:  id.sempods.org/oidc/<sha256(normalize(iss:sub))>
   *
   * The SHA-256 formula is open and stateless — any pod can derive the same URI
   * from an email without contacting sempods-auth.
   */
  private fun deriveUris(claims: OidcClaims): Pair<String, String> {
    return if (claims.email != null) {
      webIdDeriver.deriveFromEmail(claims.email) to
        WebIdUriDeriver.deriveUrnFromEmail(claims.email)
    } else {
      val hash = WebIdUriDeriver.sha256Hex("${claims.iss}:${claims.sub}")
      "${config.idBaseUrl}/oidc/$hash" to "urn:sempods:oidc:$hash"
    }
  }
}
