package org.sempods.pods.oauth.flows

import com.google.inject.Inject
import org.sempods.pods.PodId
import org.sempods.pods.grants.PodGrantsFacade
import org.sempods.pods.oauth.PodInstallationAuthorityStore
import org.sempods.pods.oauth.PodManagementAuthorityStore

/**
 * Whether an app holds anything for a person — the question that decides both whether the consent
 * dialog offers a disconnect and whether taking it means anything.
 *
 * Its grants, and an installation or management authority that still stands: one approved on its own
 * writes no grant, and a disconnect is what withdraws it before its hour is up.
 */
class PodAppHoldings @Inject internal constructor(
  private val podGrantsFacade: PodGrantsFacade,
  private val managementAuthorities: PodManagementAuthorityStore,
  private val installationAuthorities: PodInstallationAuthorityStore,
) {

  /** Asked over [webIds], every URI that names the person — a disconnect ends what any of them holds. */
  internal fun holdsAnything(pod: PodId, clientId: String, webIds: Collection<String>): Boolean =
    podGrantsFacade.appGrants(pod, clientId, webIds).isNotEmpty() ||
      managementAuthorities.standsFor(pod, clientId, webIds) ||
      installationAuthorities.standsFor(pod, clientId, webIds)
}
