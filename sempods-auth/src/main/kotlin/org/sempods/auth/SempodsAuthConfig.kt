package org.sempods.auth

import org.sempods.commons.config.Env

data class SempodsAuthConfig(
  val port: Int,
  val mongoUrl: String,
  val mongoDbName: String,
  /**
   * This service's own externally visible base URL, **without** a trailing slash.
   *
   * Every use appends a path with its own leading slash — `/e/<hash>`, `/oidc/<hash>`,
   * `/login/oidc/<provider>/callback` — so a trailing one produces `//` in a WebID URI and in the
   * redirect address registered with Apple. It is also the `issuer` an OIDC client compares byte
   * for byte between the discovery document and the token, and those two are built in different
   * places: normalising once here is what keeps them from disagreeing.
   *
   * Note this is the opposite convention to `Env.baseUrl`, which normalises *to* a trailing slash
   * for the pod server — there the base is followed by a pod name that carries no leading slash.
   */
  val idBaseUrl: String,
  /** `null` when no Google credentials are configured — then there is no Google login. */
  val google: GoogleCredentials? = null,
  /** `null` when no Apple credentials are configured — then there is no Apple login. */
  val apple: AppleCredentials? = null,
  /**
   * Content of the `apple-developer-domain-association.txt` Apple hands out when a Services ID is
   * configured for this domain, served at `/.well-known/`. `null` leaves the route unregistered.
   *
   * Normally unset: Apple's portal no longer asks for the file to configure a Services ID for web
   * login (see [org.sempods.auth.api.login.appleDomainAssociationEndpoint]). Kept separate from
   * [AppleCredentials] because the two are independent — a deployment that ever does need the
   * verification needs it *before* any credential works, and grouping them would make an Apple
   * login that is otherwise complete depend on a value it has no use for.
   *
   * A public verification token, not a secret; it comes from the environment so that the
   * repository stays free of one deployment's values.
   */
  val appleDomainAssociation: String? = null,
) {

  data class GoogleCredentials(
    val clientId: String,
    val clientSecret: String,
  )

  data class AppleCredentials(
    val teamId: String,
    val serviceId: String,
    val keyId: String,
    val privateKeyPem: String,
  )

  init {
    // A hand-built config — the local main, a test — bypasses `fromEnv`, and a trailing slash
    // there would surface as a WebID with a doubled separator rather than as an error.
    require(!idBaseUrl.endsWith("/")) { "idBaseUrl must not end with '/': $idBaseUrl" }
  }

  companion object {

    /**
     * @param lookup where a setting comes from. Injectable so a test can state the whole
     *   environment it means, rather than inheriting whatever the developer or CI runner happens
     *   to export — `Env` resolves real environment variables first, so an exported
     *   `GOOGLE_OIDC_CLIENT_ID` would otherwise decide the outcome.
     */
    fun fromEnv(lookup: (String) -> String? = Env::get): SempodsAuthConfig {
      return SempodsAuthConfig(
        port = lookup("PORT")?.toIntOrNull() ?: 8091,
        mongoUrl = lookup("MONGODB_URL") ?: "mongodb://localhost:27018",
        mongoDbName = lookup("MONGODB_DB_NAME") ?: "sempods-auth",
        idBaseUrl = (lookup("ID_BASE_URL") ?: "https://id.sempods.org").trimEnd('/'),
        google = resolveGoogle(lookup),
        apple = resolveApple(lookup),
        appleDomainAssociation = lookup("APPLE_DOMAIN_ASSOCIATION"),
      )
    }

    /**
     * Login providers are **optional**: someone running their own identity service has no reason
     * to own a Google project, so an unset variable is a valid deployment rather than an error.
     * A *partly* configured provider is a deployment mistake — [Env.group] reports it and leaves
     * the provider off rather than half-enabled.
     */
    private fun resolveGoogle(lookup: (String) -> String?): GoogleCredentials? =
      Env.group("google login", "GOOGLE_OIDC_CLIENT_ID", "GOOGLE_OIDC_CLIENT_SECRET", lookup = lookup)
        ?.let {
          GoogleCredentials(
            clientId = it.getValue("GOOGLE_OIDC_CLIENT_ID"),
            clientSecret = it.getValue("GOOGLE_OIDC_CLIENT_SECRET"),
          )
        }

    private fun resolveApple(lookup: (String) -> String?): AppleCredentials? =
      Env.group(
        "apple login",
        "APPLE_OIDC_TEAM_ID",
        "APPLE_OIDC_SERVICE_ID",
        "APPLE_OIDC_KEY_ID",
        "APPLE_PRIVATE_KEY_PEM",
        lookup = lookup,
      )?.let {
        AppleCredentials(
          teamId = it.getValue("APPLE_OIDC_TEAM_ID"),
          serviceId = it.getValue("APPLE_OIDC_SERVICE_ID"),
          keyId = it.getValue("APPLE_OIDC_KEY_ID"),
          privateKeyPem = it.getValue("APPLE_PRIVATE_KEY_PEM"),
        )
      }
  }
}
