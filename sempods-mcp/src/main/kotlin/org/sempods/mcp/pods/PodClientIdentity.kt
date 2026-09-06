package org.sempods.mcp.pods

import org.sempods.auth.core.DidWeb
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.ProfilePath

/**
 * What one profile presents to a pod: the address a pod redirects to, the name its consent screen
 * shows, and the static client identifier for a pod that offers no DCR.
 *
 * **The three fork together.** A pod resolves a person's context permissions from
 * `(pod, client_id, WebID)` and the access token carries feature scopes only — so two profiles
 * arriving as one `client_id` are one permission set, whatever the dashboard says, and a token
 * issued for `…/cron-agent` reaches whatever the person allowed in `…/private`. The only thing
 * this service can vary is what it sends, and of the fingerprint's inputs the redirect URI is the
 * one with meaning: `…/<profile>/_system/ui/pods/callback` forks the DCR digest, and a `did:web`
 * identifier scoped to the same segment forks the static path. The name is not what separates them
 * — it is what stops the pod's consent screen from listing two entries that look alike.
 *
 * **The default profile keeps what it has**: the service-wide callback, the bare name and
 * `did:web:<host>`. Its identity at every pod it is already connected to is the one it was
 * registered under, so nothing existing re-consents.
 */
object PodClientIdentity {

  /** The path both callback routes are mounted at — under the service root, and under a profile. */
  const val CALLBACK_PATH = "/_system/ui/pods/callback"

  /** The name a pod knows this service by, and the `software_id` of every registration it makes. */
  const val SERVICE_NAME = "sempods-mcp"

  /** Where the pod sends the authorization code for a connect started in [profile]. */
  fun callbackUri(serviceBaseUrl: String, profile: String): String =
    ProfilePath.baseUrlFor(serviceBaseUrl, profile) + CALLBACK_PATH

  /**
   * What the pod's consent screen calls this client. A named profile carries its own name, or the
   * person deciding what to allow would be shown two identical entries and no way to tell which is
   * the cron agent.
   */
  fun clientName(profile: String): String =
    if (isDefault(profile)) SERVICE_NAME else "$SERVICE_NAME ($profile)"

  /**
   * The static client identifier for a pod that publishes no registration endpoint. Scoped to the
   * profile's own subtree, which is what makes it a different client at the pod —
   * `DidWeb.Target.covers` matches on path segments, so `did:web:<host>:cron-agent` may receive a
   * code at `…/cron-agent/…` and nowhere else on the host.
   *
   * The DID document for it is served at `<serviceBaseUrl>/<profile>/.well-known/did.json` by
   * `oauthMetadataEndpoint`, which is what [DidWeb.clientId] requires of a caller passing a prefix.
   */
  fun didWebClientId(serviceBaseUrl: String, profile: String): String =
    DidWeb.clientId(serviceBaseUrl, if (isDefault(profile)) emptyList() else listOf(profile))

  private fun isDefault(profile: String) = profile.isBlank() || profile == PodKey.DEFAULT_PROFILE
}
