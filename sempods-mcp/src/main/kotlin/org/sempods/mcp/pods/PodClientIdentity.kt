package org.sempods.mcp.pods

import org.sempods.auth.core.DidWeb
import org.sempods.mcp.persist.PodConnection
import org.sempods.mcp.persist.PodKey
import org.sempods.mcp.persist.PodTokenFacts
import org.sempods.mcp.persist.PodTokens
import org.sempods.mcp.persist.ProfilePath

/**
 * What one profile presents to a pod: the address it redirects to, the name its consent screen
 * shows, and the static client identifier for a pod that offers no DCR. The three fork together —
 * a pod resolves permissions from `(pod, client_id, WebID)`, so two profiles arriving as one client
 * are one permission set whatever the dashboard says.
 *
 * The default profile keeps what it has, so nothing existing re-consents. The profile segment sits
 * below [CALLBACK_PATH] rather than at the service root because the session cookie is scoped to
 * `WebSession.COOKIE_PATH` and a browser sends it nowhere else; the `did:web` identifier follows
 * the path because it has to cover it.
 *
 * Per profile and not per service user: two accounts signing in at one pod as the same WebID are
 * one person to that pod, and it holds one grant set for that identity whatever this service sends.
 *
 * `docs/concepts/hosted-mcp.md` §"Connecting a pod (OAuth)" carries the reasoning.
 */
object PodClientIdentity {

  /** The address a pod redirects to for the default profile, and the parent of every named one. */
  const val CALLBACK_PATH = "/_system/ui/pods/callback"

  /**
   * [CALLBACK_PATH] as `did:web` components. Checked here rather than left to [DidWeb.clientId],
   * which only sees them when a named profile connects to a pod that publishes no DCR.
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
   * Which identity a stored callback address is — the inverse of [callbackUri], living here so the
   * name is read off the same value the registration is pinned to.
   *
   * The default profile for the parent address, for `null`, and for an address this service would
   * not mint today (a changed `MCP_BASE_URL`), which behaves like the shared identity anyway.
   */
  fun profileOf(serviceBaseUrl: String, callbackUri: String?): String {
    val named = "${callbackUri(serviceBaseUrl, PodKey.DEFAULT_PROFILE)}/"
    val segment = callbackUri?.takeIf { it.startsWith(named) }?.removePrefix(named)
    return segment?.takeIf(ProfilePath::isValidName) ?: PodKey.DEFAULT_PROFILE
  }

  /**
   * The registration to present: the token row's complete client-id/redirect-URI pair, or the
   * registry [fallback] when either field is absent. Off **one** row, never one field from each —
   * the pod refuses an id offered under an address it was not registered with.
   *
   * Takes the projection, because that is where a null is still possible: on [PodTokens] both are
   * required, so what needs resolving here is a document the refresh path reads as unreadable and
   * the surfaces that only report a connection still show.
   */
  fun registrationOf(facts: PodTokenFacts?, fallback: PodConnection): PodRegistration =
    if (facts?.podClientId != null && facts.podRedirectUri != null) PodRegistration(facts.podClientId, facts.podRedirectUri)
    else PodRegistration(fallback.podClientId, fallback.podRedirectUri)

  /** What the pod's consent screen calls this client, or two profiles list as identical entries. */
  fun clientName(profile: String): String =
    if (isDefault(profile)) SERVICE_NAME else "$SERVICE_NAME ($profile)"

  /**
   * The static client identifier for a pod that publishes no registration endpoint, scoped to the
   * profile's own callback: `DidWeb.Target.covers` matches path segments, so it may receive a code
   * at that one address and nowhere else on the host.
   *
   * `oauthMetadataEndpoint` serves its DID document at `<callbackUri>/did.json`, which is what
   * [DidWeb.clientId] requires of a caller passing a prefix.
   */
  fun didWebClientId(serviceBaseUrl: String, profile: String): String =
    DidWeb.clientId(
      serviceBaseUrl,
      if (isDefault(profile)) emptyList() else CALLBACK_SEGMENTS + profile,
    )

  private fun isDefault(profile: String) = profile.isBlank() || profile == PodKey.DEFAULT_PROFILE
}

/** What a connection presents at the pod: a `client_id` and the address that id is pinned to. */
data class PodRegistration(val clientId: String, val redirectUri: String?)
