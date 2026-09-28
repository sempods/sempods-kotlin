package org.sempods.pods.grants

import org.sempods.pods.oauth.serviceclients.ServiceClientRegistrationId

/**
 * Who receives the grants [PodGrantsFacade.replaceGrants] writes, and the conflict rule that write
 * follows.
 *
 * [R] is what a replace can answer for this recipient, so the rule shows in the type:
 * a [Delegation] is always [GrantReplacement.Replaced], a [Service] may also be refused.
 */
internal sealed interface GrantRecipient<out R : GrantReplacement> {

  /**
   * An app acting for the person [webId], who is also known by [aliases] (`identity.allUris`).
   *
   * **No version.** Of two submissions one after the other, the later stands. Overlapping ones are
   * not atomic (`PodGrantsDao.replaceGrants`, #338).
   *
   * A selection may carry the feature scope `public-read` beside its context grants.
   */
  data class Delegation(
    val clientId: String,
    val webId: String,
    val aliases: Collection<String>,
  ) : GrantRecipient<GrantReplacement.Replaced>

  /**
   * A service client acting as itself: the registration [registrationId] stored under [clientId].
   *
   * **Optimistic.** The replace writes only while the grants are still at [expectedVersion]
   * (`PodServiceClientDbo.grantsVersion`), and answers [GrantReplacement.Conflict] once any
   * other write changed them. A registration re-created under the same [clientId] is another one,
   * and answers [GrantReplacement.NotFound].
   *
   * A selection holds context grants only; anything else throws [IllegalArgumentException].
   */
  data class Service(
    val registrationId: ServiceClientRegistrationId,
    val clientId: String,
    val expectedVersion: Long,
  ) : GrantRecipient<GrantReplacement>
}

/** What [PodGrantsFacade.replaceGrants] did. Only [Replaced] wrote anything. */
internal sealed interface GrantReplacement {

  /**
   * The selection stands. [granted] is what of it survived the check after the write, which drops
   * what lost its backing in the meantime.
   */
  data class Replaced(val granted: Set<String>) : GrantReplacement

  /** The grants changed since the version the replace was prepared at. */
  data object Conflict : GrantReplacement

  /** The registration is gone, or was replaced by one under the same `clientId`. */
  data object NotFound : GrantReplacement
}
