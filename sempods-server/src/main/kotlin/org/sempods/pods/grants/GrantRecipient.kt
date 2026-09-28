package org.sempods.pods.grants

import org.sempods.pods.oauth.serviceclients.ServiceClientRegistrationId

/**
 * Who receives the grants [PodGrantsFacade.replaceGrants] writes, and the conflict rule that write
 * follows.
 *
 * [R] is what a replace can answer for this recipient, so the rule shows in the type:
 * a [Delegation] is always [GrantReplacement.Replaced], a [Service] may also be refused.
 *
 * The two are stored apart and stay apart: delegated grants in `grants`, a service's on its
 * registration row. The context-deletion sweep over `grants` re-derives every row from a person's
 * authority, which a service's grants do not come from; and a registration's grants share one
 * document with the rest of its state, which is what lets a single update change both.
 */
internal sealed interface GrantRecipient<out R : GrantReplacement> {

  val clientId: String

  /**
   * An app acting for the person [webId], who is also known by [aliases] (`identity.allUris`).
   *
   * **No version.** Of two submissions one after the other, the later stands. Overlapping ones are
   * not atomic: the replace deletes and then inserts (`PodGrantsDao.replaceGrants`), so two
   * interleaved can leave the union of both selections. #338 owns that.
   *
   * A selection may carry the feature scope `public-read` beside its context grants.
   */
  data class Delegation(
    override val clientId: String,
    val webId: String,
    val aliases: Collection<String>,
  ) : GrantRecipient<GrantReplacement.Replaced>

  /**
   * A service client acting as itself: the registration [registrationId] stored under [clientId].
   *
   * **Optimistic.** The replace writes only while the grants are still at [expectedVersion]
   * (`ServiceClientRegistration.grantsVersion`), and answers [GrantReplacement.Conflict] once any
   * other write changed them. A registration re-created under the same [clientId] is another one,
   * and answers [GrantReplacement.NotFound].
   *
   * A selection holds context grants only; anything else throws [IllegalArgumentException].
   */
  data class Service(
    val registrationId: ServiceClientRegistrationId,
    override val clientId: String,
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
