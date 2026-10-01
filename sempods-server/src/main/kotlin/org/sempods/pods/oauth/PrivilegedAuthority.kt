package org.sempods.pods.oauth

import org.sempods.pods.PodId

/**
 * A privileged authority as [PrivilegedAuthorityRows] records it: who granted which app what, and
 * on which pod.
 *
 * @param pod the pod the authority was granted on.
 * @param clientId the app the person authorized — the program holding the bearer, not a service
 *   client it creates or manages.
 * @param webId the person who granted it.
 * @param disconnects `PodConsentDecisionStore.Decision.disconnects` when the authority was
 *   granted; a disconnect since withdraws it. Not the consent generation, which every privileged
 *   consent moves — one authority would then withdraw the other.
 * @param subjectUris every identity URI the person was recognised by at the dialog, [webId] among
 *   them. An empty set recognises nobody.
 * @param consent the version of the consent text the person approved for the bearer's scope
 *   ([PrivilegedAuthorityRows.consentTextOf]). A row without one was approved under the first text.
 */
internal data class PrivilegedAuthority(
  val pod: PodId,
  val clientId: String,
  val webId: String,
  val disconnects: Long,
  val subjectUris: Set<String>,
  val consent: Int,
)
