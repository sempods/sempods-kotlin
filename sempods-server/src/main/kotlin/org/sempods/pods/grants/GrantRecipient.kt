package org.sempods.pods.grants

import org.sempods.pods.oauth.serviceclients.ServiceClientRegistrationId

/**
 * Who receives the grants [PodGrantsFacade.replaceGrants] writes. [R] is what the replace can answer,
 * so each recipient's conflict rule shows in the type.
 */
internal sealed interface GrantRecipient<out R : GrantReplacement> {

  /**
   * An app acting for [webId], also known by [aliases]. **No version:** the later of two replaces
   * stands; overlapping ones are not atomic (`PodGrantsDao.replaceGrants`). May include `public-read`.
   */
  data class Delegation(
    val clientId: String,
    val webId: String,
    val aliases: Collection<String>,
  ) : GrantRecipient<GrantReplacement.Replaced>

  /**
   * A service client acting as itself. **Optimistic:** writes only at [expectedVersion]
   * (`PodServiceClientDbo.grantsVersion`), else [GrantReplacement.Conflict]; a registration
   * re-created under [clientId] is [GrantReplacement.NotFound]. Context grants only.
   */
  data class Service(
    val registrationId: ServiceClientRegistrationId,
    val clientId: String,
    val expectedVersion: Long,
  ) : GrantRecipient<GrantReplacement>
}

/** What [PodGrantsFacade.replaceGrants] did. Only [Replaced] wrote anything. */
internal sealed interface GrantReplacement {

  /** The selection stands; [granted] is what survived the check after the write. */
  data class Replaced(val granted: Set<String>) : GrantReplacement

  /** The grants changed since the version the replace was prepared at. */
  data object Conflict : GrantReplacement

  /** The registration is gone, or was replaced by one under the same `clientId`. */
  data object NotFound : GrantReplacement
}
