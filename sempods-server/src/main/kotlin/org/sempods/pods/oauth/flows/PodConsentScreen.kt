package org.sempods.pods.oauth.flows

import java.net.URI
import org.sempods.auth.ConsentBinding
import org.sempods.pods.oauth.ConnectionTerms


/**
 * What this authorization made of this person, this client and this pod — everything the consent
 * dialog shows, decided. The presentation around it is `PodAuthorizeResponses`'.
 *
 * @param clientName what to call the client — its registered name where it has one, its `client_id`
 *   otherwise, so the dialog never shows a blank.
 * @param clientUri where the client says it lives, and [logoUri] its logo; `null` where it named
 *   none or named one `ClientMetadataUri` refuses.
 * @param binding the request this screen answers and what it offers, as its [csrfToken] records it.
 *   The template renders the `public-read` box and context creation from here, so the page cannot
 *   offer what the submission would refuse.
 * @param csrfToken this screen's one-time ticket — see where it is issued.
 * @param sessionTerms how long a connection lives when the person leaves the durability box
 *   unticked, [durableTerms] when they tick it. Both come from the store that will enforce them, so
 *   the dialog cannot state a term the deployment does not keep.
 * @param disconnectAvailable whether this app holds anything for this person — whether the way out
 *   is worth offering.
 * @param privilegedFeatures the privileged feature scopes this request asked for, in the order the
 *   dialog shows them — empty on an ordinary authorization. Each is offered unticked: a person
 *   approves a capability like managing the pod's service clients by choosing it, never by leaving a box
 *   as it was found.
 * @param lifetimeAvailable whether the dialog carries the lifetime control at all. False wherever
 *   [privilegedFeatures] is non-empty, so that ticking an ordinary box cannot turn an hour-long
 *   authority into a renewable one; [sessionTerms] and [durableTerms] then have nothing to state.
 */
internal data class PodConsentScreen(
  val podName: String,
  val podBaseUrl: String,
  val clientId: String,
  val clientName: String,
  val clientUri: String?,
  val logoUri: String?,
  val binding: ConsentBinding,
  val csrfToken: String,
  val webId: String,
  val contexts: List<PodConsentContext>,
  val isOwner: Boolean,
  val publicContexts: List<String>,
  val publicReadPreselected: Boolean,
  val durablePreselected: Boolean,
  val sessionTerms: ConnectionTerms,
  val durableTerms: ConnectionTerms,
  val disconnectAvailable: Boolean,
  val privilegedFeatures: List<String>,
  val lifetimeAvailable: Boolean,
)

/**
 * One context in the dialog, with the three permissions it can carry.
 *
 * **The property names are the template's.** `templates/consent.html` reads `ctx.uri`,
 * `ctx.relativePath`, `ctx.readGranted`, `ctx.writeGranted`, `ctx.manageGranted` and
 * `ctx.managedVia` by reflection, so renaming one here fails at render time and not at compile time.
 *
 * @param managedVia the relative path of a context this app already holds `#manage` on and this
 *   row lies below, `null` otherwise. Shown as a note without a box: the grant it describes is
 *   the root's.
 */
internal data class PodConsentContext(
  val uri: String,
  val relativePath: String,
  val label: String,
  val readGranted: Boolean,
  val writeGranted: Boolean,
  val manageGranted: Boolean,
  val managedVia: String? = null,
) {
  companion object {
    /** [uri]'s path after the pod name segment: `…/alice/public/tasks` is `public/tasks`. */
    fun relativePathOf(uri: String): String = (URI(uri).path?.trimStart('/') ?: uri).let { it.substringAfter('/', it) }
  }
}
