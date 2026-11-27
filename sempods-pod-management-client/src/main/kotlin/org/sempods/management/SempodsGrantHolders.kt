package org.sempods.management

import org.sempods.client.SempodsPermission
import java.time.Instant
import java.util.Collections
import java.util.EnumSet

/**
 * Who holds grants reaching one context of a pod, as [SempodsPodGrants.holders] reads it. Grouped by
 * person: an app acting for a person is listed under them, because withdrawing the person withdraws
 * it too. The pod owner holds no grants of their own, so their apps are [apps].
 */
class SempodsContextHolders private constructor(
  /** The context, as the context catalogue names it. */
  val contextIri: String,
  people: List<SempodsGrantPerson>,
  apps: List<SempodsGrantApp>,
  services: List<SempodsGrantService>,
) {

  /** The people other than the owner who reach the context, ordered by WebID. */
  val people: List<SempodsGrantPerson> = Collections.unmodifiableList(people.toList())

  /** The owner's own apps that reach the context, ordered by client identifier. */
  val apps: List<SempodsGrantApp> = Collections.unmodifiableList(apps.toList())

  /** The service clients that reach the context acting as themselves, ordered by client identifier. */
  val services: List<SempodsGrantService> = Collections.unmodifiableList(services.toList())

  override fun equals(other: Any?): Boolean =
    other is SempodsContextHolders && other.contextIri == contextIri && other.people == people && other.apps == apps &&
      other.services == services

  override fun hashCode(): Int = listOf(contextIri, people, apps, services).hashCode()

  override fun toString(): String = "SempodsContextHolders(contextIri=$contextIri, people=$people, apps=$apps, services=$services)"

  internal companion object {

    @JvmSynthetic
    internal fun of(contextIri: String, people: List<SempodsGrantPerson>, apps: List<SempodsGrantApp>, services: List<SempodsGrantService>) =
      SempodsContextHolders(contextIri, people, apps, services)
  }
}

/**
 * A person reaching a context, with the apps acting for them there. One entry per person, however
 * many WebIDs they hold grants under; withdrawing names any of them.
 *
 * [grants] is empty where only their [delegations] reach it: the delegations of someone who holds
 * no grant there any more, such as a former owner. Withdrawing the person withdraws the direct ones
 * on this context.
 */
class SempodsGrantPerson private constructor(
  val webId: String,
  permissions: Set<SempodsPermission>,
  grants: List<SempodsReachingGrant>,
  delegations: List<SempodsGrantApp>,
) {

  /**
   * What the person can do on the context, as the context catalogue reports it: every permission
   * their grants imply (`SPS-GRANT-009`), in declaration order.
   */
  val permissions: Set<SempodsPermission> = permissionSet(permissions)

  /** The person's grants that reach the context, each with where it is anchored. */
  val grants: List<SempodsReachingGrant> = Collections.unmodifiableList(grants.toList())

  /** The apps acting for this person that reach the context. */
  val delegations: List<SempodsGrantApp> = Collections.unmodifiableList(delegations.toList())

  override fun equals(other: Any?): Boolean =
    other is SempodsGrantPerson && other.webId == webId && other.permissions == permissions && other.grants == grants &&
      other.delegations == delegations

  override fun hashCode(): Int = listOf(webId, permissions, grants, delegations).hashCode()

  override fun toString(): String = "SempodsGrantPerson(webId=$webId, permissions=$permissions, grants=$grants, delegations=$delegations)"

  internal companion object {

    @JvmSynthetic
    internal fun of(webId: String, permissions: Set<SempodsPermission>, grants: List<SempodsReachingGrant>, delegations: List<SempodsGrantApp>) =
      SempodsGrantPerson(webId, permissions, grants, delegations)
  }
}

/** An app acting for a person — or for the owner, in [SempodsContextHolders.apps]. */
class SempodsGrantApp private constructor(
  val clientId: String,
  /** The name it registered with, and null where the pod holds no registration naming it. */
  val clientName: String?,
  permissions: Set<SempodsPermission>,
  grants: List<SempodsReachingGrant>,
  /**
   * When it last received a refresh token for this person, and null where the pod records none: an
   * app that never held one, or whose tokens have expired. Not the time of its last request.
   */
  val lastUsedAt: Instant?,
) {

  /** What the app can do on the context, as the context catalogue reports it. */
  val permissions: Set<SempodsPermission> = permissionSet(permissions)

  /** Its grants that reach the context, each with where it is anchored. */
  val grants: List<SempodsReachingGrant> = Collections.unmodifiableList(grants.toList())

  override fun equals(other: Any?): Boolean =
    other is SempodsGrantApp && other.clientId == clientId && other.clientName == clientName && other.permissions == permissions &&
      other.grants == grants && other.lastUsedAt == lastUsedAt

  override fun hashCode(): Int = listOf(clientId, clientName, permissions, grants, lastUsedAt).hashCode()

  override fun toString(): String =
    "SempodsGrantApp(clientId=$clientId, clientName=$clientName, permissions=$permissions, grants=$grants, lastUsedAt=$lastUsedAt)"

  internal companion object {

    @JvmSynthetic
    internal fun of(clientId: String, clientName: String?, permissions: Set<SempodsPermission>, grants: List<SempodsReachingGrant>, lastUsedAt: Instant?) =
      SempodsGrantApp(clientId, clientName, permissions, grants, lastUsedAt)
  }
}

/** A service client reaching a context acting as itself. */
class SempodsGrantService private constructor(
  val clientId: String,
  /** The name its registration carries, and null when it has none. */
  val clientName: String?,
  permissions: Set<SempodsPermission>,
  grants: List<SempodsReachingGrant>,
  /** When it last obtained a token, and null when it never has. */
  val lastUsedAt: Instant?,
  /**
   * The version its grants are at, which [SempodsPodGrants.withdrawService] names. The same number as
   * [org.sempods.client.SempodsServiceClient.grantsVersion].
   */
  val grantsVersion: Long,
) {

  /**
   * What the service can do on the context, as the context catalogue reports it. Empty for a service
   * holding the management authority, whose tokens reach no data; its [grants] are still withdrawn.
   */
  val permissions: Set<SempodsPermission> = permissionSet(permissions)

  /** Its grants that reach the context, each with where it is anchored. */
  val grants: List<SempodsReachingGrant> = Collections.unmodifiableList(grants.toList())

  override fun equals(other: Any?): Boolean =
    other is SempodsGrantService && other.clientId == clientId && other.clientName == clientName && other.permissions == permissions &&
      other.grants == grants && other.lastUsedAt == lastUsedAt && other.grantsVersion == grantsVersion

  override fun hashCode(): Int = listOf(clientId, clientName, permissions, grants, lastUsedAt, grantsVersion).hashCode()

  override fun toString(): String =
    "SempodsGrantService(clientId=$clientId, clientName=$clientName, permissions=$permissions, grants=$grants, " +
      "lastUsedAt=$lastUsedAt, grantsVersion=$grantsVersion)"

  internal companion object {

    @JvmSynthetic
    internal fun of(
      clientId: String,
      clientName: String?,
      permissions: Set<SempodsPermission>,
      grants: List<SempodsReachingGrant>,
      lastUsedAt: Instant?,
      grantsVersion: Long,
    ) = SempodsGrantService(clientId, clientName, permissions, grants, lastUsedAt, grantsVersion)
  }
}

/**
 * One grant that reaches a context. Only a direct one is withdrawn on the context; an inherited one
 * is withdrawn on [inheritedFrom], and with it on every context it covers.
 */
class SempodsReachingGrant private constructor(
  /** The permission the grant names, without what it implies. */
  val permission: SempodsPermission,
  /** The ancestor whose `#manage` grant this is, and null for a grant anchored at the context itself. */
  val inheritedFrom: String?,
) {

  /** Whether the grant is anchored at the context itself. */
  val isDirect: Boolean get() = inheritedFrom == null

  override fun equals(other: Any?): Boolean =
    other is SempodsReachingGrant && other.permission == permission && other.inheritedFrom == inheritedFrom

  override fun hashCode(): Int = listOf(permission, inheritedFrom).hashCode()

  override fun toString(): String = "SempodsReachingGrant(permission=$permission, inheritedFrom=$inheritedFrom)"

  internal companion object {

    @JvmSynthetic
    internal fun of(permission: SempodsPermission, inheritedFrom: String?) = SempodsReachingGrant(permission, inheritedFrom)
  }
}

/** How many of each group reach one context, as [SempodsPodGrants.counts] reads them. Inherited grants count. */
class SempodsContextHolderCounts private constructor(
  val contextIri: String,
  /** People other than the owner. */
  val people: Int,
  /** The owner's own apps. */
  val apps: Int,
  /** Service clients acting as themselves. */
  val services: Int,
) {

  override fun equals(other: Any?): Boolean =
    other is SempodsContextHolderCounts && other.contextIri == contextIri && other.people == people && other.apps == apps &&
      other.services == services

  override fun hashCode(): Int = listOf(contextIri, people, apps, services).hashCode()

  override fun toString(): String = "SempodsContextHolderCounts(contextIri=$contextIri, people=$people, apps=$apps, services=$services)"

  internal companion object {

    @JvmSynthetic
    internal fun of(contextIri: String, people: Int, apps: Int, services: Int) = SempodsContextHolderCounts(contextIri, people, apps, services)
  }
}

/** An unmodifiable copy of [permissions] in [SempodsPermission]'s order; `EnumSet.copyOf` refuses an empty collection. */
private fun permissionSet(permissions: Collection<SempodsPermission>): Set<SempodsPermission> =
  Collections.unmodifiableSet(EnumSet.noneOf(SempodsPermission::class.java).apply { addAll(permissions) })
