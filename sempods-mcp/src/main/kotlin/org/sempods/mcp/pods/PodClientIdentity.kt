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
 * one with meaning: `…/_system/ui/pods/callback/<profile>` forks the DCR digest, and a `did:web`
 * identifier scoped to that same path forks the static one. The name is not what separates them —
 * it is what stops the pod's consent screen from listing two entries that look alike.
 *
 * **The default profile keeps what it has**: the service-wide callback, the bare name and
 * `did:web:<host>`. Its identity at every pod it is already connected to is the one it was
 * registered under, so nothing existing re-consents.
 *
 * **Per profile, and not per service user**, which is the pod's line rather than a shortcut here.
 * Two service accounts that both name a profile `cron-agent` and both sign in at one pod as the
 * same WebID arrive as one client — and to that pod they are one person, holding one grant set and
 * one refresh-token family for that identity. Nothing this service sends can make it hold two;
 * what separates people there is the WebID they sign in as. See `docs/concepts/hosted-mcp.md`
 * §"Connecting a pod (OAuth)".
 *
 * **The profile segment sits below [CALLBACK_PATH], not at the service root**, and that is a
 * requirement rather than a taste: the web session is an httpOnly cookie scoped to
 * `WebSession.COOKIE_PATH`, and RFC 6265 §5.1.4 sends it only to paths below that one. A callback
 * at `…/<profile>/_system/ui/pods/callback` would arrive without a session in a real browser, the
 * handler would send the person to sign in instead of exchanging the code, and no named profile
 * could ever finish a connect. The `did:web` identifier follows the path because it has to cover
 * it, so it names the same segments.
 */
object PodClientIdentity {

  /** The address a pod redirects to for the default profile, and the parent of every named one. */
  const val CALLBACK_PATH = "/_system/ui/pods/callback"

  /**
   * [CALLBACK_PATH] as `did:web` components — the prefix a named profile's identifier scopes to.
   *
   * Checked here rather than left to [DidWeb.clientId], which only sees these when a named profile
   * connects to a pod that publishes no registration endpoint. A [CALLBACK_PATH] carrying a
   * character the DID grammar has no room for would otherwise compile, boot, and serve every
   * default-profile flow before failing that one connect as "could not reach pod".
   */
  private val CALLBACK_SEGMENTS = CALLBACK_PATH.split("/").filter { it.isNotEmpty() }
    .onEach {
      require(DidWeb.SEGMENT_CHARS.matches(it)) { "CALLBACK_PATH segment is not a did:web segment: '$it'" }
    }

  /** The name a pod knows this service by, and the `software_id` of every registration it makes. */
  const val SERVICE_NAME = "sempods-mcp"

  /** Where the pod sends the authorization code for a connect started in [profile]. */
  fun callbackUri(serviceBaseUrl: String, profile: String): String =
    serviceBaseUrl + CALLBACK_PATH + if (isDefault(profile)) "" else "/$profile"

  /**
   * Which identity a stored callback address is — the inverse of [callbackUri], and the reason
   * both live here: a connection's identity at a pod is the address its registration is pinned to,
   * so the name that goes with it has to be read off the same value or the pod's fingerprint
   * dedups a re-registration to a different client.
   *
   * The default profile for the parent address, and for `null` — a connection made while every
   * profile shared one client. Also for an address this service would not mint today, which a
   * changed `MCP_BASE_URL` produces: guessing a profile out of it would be worse than treating it
   * as the shared identity it behaves like.
   */
  fun profileOf(serviceBaseUrl: String, callbackUri: String?): String {
    val named = "${callbackUri(serviceBaseUrl, PodKey.DEFAULT_PROFILE)}/"
    val segment = callbackUri?.takeIf { it.startsWith(named) }?.removePrefix(named)
    return segment?.takeIf(ProfilePath::isValidName) ?: PodKey.DEFAULT_PROFILE
  }

  /**
   * What the pod's consent screen calls this client. A named profile carries its own name, or the
   * person deciding what to allow would be shown two identical entries and no way to tell which is
   * the cron agent.
   */
  fun clientName(profile: String): String =
    if (isDefault(profile)) SERVICE_NAME else "$SERVICE_NAME ($profile)"

  /**
   * The static client identifier for a pod that publishes no registration endpoint. Scoped to the
   * profile's own callback, which is what makes it a different client at the pod —
   * `DidWeb.Target.covers` matches on path segments, so
   * `did:web:<host>:_system:ui:pods:callback:cron-agent` may receive a code at that one address and
   * nowhere else on the host.
   *
   * The DID document for it is served at `<callbackUri>/did.json` by `oauthMetadataEndpoint` —
   * where the did:web read algorithm looks for a path-scoped identifier, `/.well-known` being
   * inserted only where there is no path. That is what [DidWeb.clientId] requires of a caller
   * passing a prefix.
   */
  fun didWebClientId(serviceBaseUrl: String, profile: String): String =
    DidWeb.clientId(
      serviceBaseUrl,
      if (isDefault(profile)) emptyList() else CALLBACK_SEGMENTS + profile,
    )

  private fun isDefault(profile: String) = profile.isBlank() || profile == PodKey.DEFAULT_PROFILE
}
