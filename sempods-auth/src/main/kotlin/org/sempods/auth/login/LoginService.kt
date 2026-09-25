package org.sempods.auth.login

import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.auth.SempodsAuthConfig
import org.sempods.auth.core.EquivalentIdentities
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
   * The other WebIDs of the person [webIdUri] names, for the ID Token's equivalent-identity claim
   * (`SPS-OIDC-005`).
   *
   * They are the links an identity merge recorded on the profile. The claim carries HTTP and HTTPS
   * WebIDs only, so each link goes out in that form:
   *
   * | Recorded link | In the claim |
   * |---|---|
   * | `{idBaseUrl}/oidc/<hash>`, `https://alice.example/card#me` | as recorded |
   * | `urn:sempods:e:<hash>` | its twin `{idBaseUrl}/e/<hash>` (`WebIdUriDeriver.derivableAliases`) |
   * | anything else | left out: one bad entry makes a relying party refuse the whole login |
   *
   * The URN twin of [webIdUri] itself is not sent. A pod derives it from `sub`, which is what an
   * invitation made before the first login resolves against (`identity-service.md`
   * §"Email → Grant Flow").
   */
  fun equivalentIdentitiesFor(webIdUri: String, linkedIdentities: List<String>? = null): List<String> {
    val linked = linkedIdentities ?: webIdProfileDao.findByUri(webIdUri)?.linkedIdentities.orEmpty()
    return linked
      .flatMap(webIdDeriver::derivableAliases)
      .filter(EquivalentIdentities::isWebIdUri)
      .distinct()
      .filter { it != webIdUri }
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
