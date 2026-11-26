package org.sempods.pods.grants

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.HostedPod
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantDbo
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.objectId
import org.sempods.pods.oauth.DynamicClientStore
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.time.Instant

/**
 * Who reaches the contexts of a pod, as its owner sees it: the people holding grants, the apps
 * acting for each of them, the owner's own apps, and the services acting as themselves.
 *
 * **The person is the unit.** A delegation is keyed by app and WebID and is bounded by that
 * person's grants at consent ([PodGrantsFacade]), so it is listed under the person and goes with
 * them. The owner holds no WebID grants — their authority is implicit — so their delegations are a
 * group of their own.
 *
 * Read in one pass: the registry, both grant levels, the registrations and the refresh rows, each
 * once, however many contexts the pod has. [snapshot] answers every context from that pass.
 *
 * Only registered contexts are answered. A grant naming an unregistered one reaches nothing; the
 * context-deletion sweep removes such grants.
 */
class PodGrantHolders @Inject internal constructor(
  private val podContextsDao: PodContextsDao,
  private val podWebIdGrantsDao: PodWebIdGrantsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podServiceClientStore: PodServiceClientStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val dynamicClientStore: DynamicClientStore,
  private val podScopeValidator: PodScopeValidator,
  private val podContextPermissionResolver: PodContextPermissionResolver,
  private val podGrantsFacade: PodGrantsFacade,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  /** Everyone holding a grant on [pod], ready to be asked about any registered context. */
  internal fun snapshot(pod: HostedPod): GrantHoldersSnapshot {
    val storedPodId = pod.id.objectId()
    val contexts = podContextsDao.fetchByPod(storedPodId).map { it.contextUri }.sorted()
    val lastIssued = refreshTokenStore.lastIssuedPerConnection(pod.id)

    val people = people(pod)
    val delegations = podGrantsDao.fetchGrantsByPod(storedPodId)
    val ownerRows = mutableListOf<PodGrantDbo>()
    val rowsByPerson = linkedMapOf<PersonKey, MutableList<PodGrantDbo>>()
    delegations.forEach { row ->
      val uris = rowUris(row)
      when {
        podGrantsFacade.isPodOwner(pod, uris) -> ownerRows += row
        else -> {
          // A row that names nobody holding grants is still a person's: a former owner's, or one a
          // cascade has not reached. It is listed, and withdrawing the person ends it.
          val person = people.firstOrNull { it.uris.any(uris::contains) }
            ?: PersonKey(row.webId, webIdUriDeriver.derivableAliases(row.webId) + row.webId).also { people += it }
          rowsByPerson.getOrPut(person) { mutableListOf() } += row
        }
      }
    }

    val names = mutableMapOf<String, String?>()
    fun appName(clientId: String): String? = names.getOrPut(clientId) {
      dynamicClientStore.lookup(pod.id, clientId)?.clientName?.takeIf { it.isNotBlank() }
    }
    fun apps(rows: List<PodGrantDbo>): List<HeldApp> = rows.groupBy { it.appId }.map { (appId, appRows) ->
      HeldApp(
        clientId = appId,
        name = appName(appId),
        grants = parse(pod, appRows.map { it.scope }),
        lastUsedAt = appRows.flatMap(::rowUris).mapNotNull { lastIssued[appId to it] }.maxOrNull(),
      )
    }

    val heldPeople = people.map { person ->
      HeldPerson(
        webId = person.webId,
        grants = parse(pod, person.grants),
        delegations = apps(rowsByPerson[person].orEmpty()),
      )
    }
    val services = podServiceClientStore.list(pod.id).map { registration ->
      HeldService(
        clientId = registration.clientId,
        name = registration.label?.takeIf { it.isNotBlank() },
        grants = parse(pod, registration.scopes),
        lastUsedAt = registration.lastUsedAt,
        grantsVersion = registration.grantsVersion,
      )
    }
    return GrantHoldersSnapshot(
      contexts = contexts,
      people = heldPeople,
      ownerApps = apps(ownerRows),
      services = services,
      resolver = podContextPermissionResolver,
    )
  }

  /**
   * The people holding WebID grants on [pod], other than its owner, with the URIs of one person
   * merged: a grant written under either derivable spelling is the same person's.
   */
  private fun people(pod: HostedPod): MutableList<PersonKey> {
    val rows = podWebIdGrantsDao.fetchByPod(pod.id.objectId())
      .filterNot { podGrantsFacade.isPodOwner(pod, it.webId) }
    val people = mutableListOf<PersonKey>()
    rows.groupBy { it.webId }.forEach { (webId, grants) ->
      val aliases = webIdUriDeriver.derivableAliases(webId) + webId
      val known = people.firstOrNull { person -> person.uris.any(aliases::contains) }
      if (known == null) {
        people += PersonKey(webId, aliases, grants.mapTo(mutableSetOf()) { it.scope })
      } else {
        known.grants += grants.map { it.scope }
        // The dereferenceable spelling names the person in the answer where there is one.
        if (!known.webId.startsWith("http") && webId.startsWith("http")) known.webId = webId
      }
    }
    return people
  }

  /** Every URI [row] knows its person by: its WebID, their twins, and the URIs consented under. */
  private fun rowUris(row: PodGrantDbo): Set<String> =
    webIdUriDeriver.derivableAliases(row.webId) + row.webId + row.subjectUris.orEmpty()

  /** The context grants among [scopes], parsed once; feature scopes such as `public-read` drop out. */
  private fun parse(pod: HostedPod, scopes: Collection<String>): List<ScopeValidationResult.Context> =
    scopes.mapNotNull { podScopeValidator.validate(it, pod.baseUrl) as? ScopeValidationResult.Context }.distinct()

  /** A person being grouped: identity equality, since [webId] may change to a better spelling. */
  private class PersonKey(
    var webId: String,
    val uris: Set<String>,
    val grants: MutableSet<String> = mutableSetOf(),
  )
}

/**
 * Everyone holding a grant on one pod, read once by [PodGrantHolders.snapshot]. [on] answers one
 * context, [counts] every context.
 */
internal class GrantHoldersSnapshot(
  /** The registered contexts, ascending. */
  val contexts: List<String>,
  private val people: List<HeldPerson>,
  private val ownerApps: List<HeldApp>,
  private val services: List<HeldService>,
  private val resolver: PodContextPermissionResolver,
) {

  /** Who reaches [contextUri]; `null` where no such context is registered. */
  fun on(contextUri: String): ContextHolders? {
    if (contextUri !in contexts) return null
    return ContextHolders(
      contextUri = contextUri,
      people = people.mapNotNull { person ->
        val delegations = person.delegations.mapNotNull { it.on(contextUri) }
        val grants = reaching(person.grants, contextUri)
        if (grants.isEmpty() && delegations.isEmpty()) null
        else PersonHolder(webId = person.webId, grants = grants, delegations = delegations)
      }.sortedBy { it.webId },
      ownerApps = ownerApps.mapNotNull { it.on(contextUri) }.sortedBy { it.clientId },
      services = services.mapNotNull { service ->
        reaching(service.grants, contextUri).takeIf { it.isNotEmpty() }?.let { grants ->
          ServiceHolder(service.clientId, service.name, grants, service.lastUsedAt, service.grantsVersion)
        }
      }.sortedBy { it.clientId },
    )
  }

  /** How many of each group reach every registered context, ascending by context. */
  fun counts(): List<ContextHolderCounts> = contexts.map { contextUri ->
    val holders = checkNotNull(on(contextUri))
    ContextHolderCounts(
      contextUri = contextUri,
      people = holders.people.size,
      ownerApps = holders.ownerApps.size,
      services = holders.services.size,
    )
  }

  private fun HeldApp.on(contextUri: String): AppHolder? =
    reaching(grants, contextUri).takeIf { it.isNotEmpty() }?.let { AppHolder(clientId, name, it, lastUsedAt) }

  private fun reaching(grants: List<ScopeValidationResult.Context>, contextUri: String): List<ReachingGrant> =
    grants.mapNotNull { resolver.reaching(it, contextUri) }
      .distinct()
      .sortedWith(compareBy<ReachingGrant>({ it.inheritedFrom ?: "" }, { it.permission.ordinal }))
}

internal class HeldPerson(
  val webId: String,
  val grants: List<ScopeValidationResult.Context>,
  val delegations: List<HeldApp>,
)

internal class HeldApp(
  val clientId: String,
  val name: String?,
  val grants: List<ScopeValidationResult.Context>,
  val lastUsedAt: Instant?,
)

internal class HeldService(
  val clientId: String,
  val name: String?,
  val grants: List<ScopeValidationResult.Context>,
  val lastUsedAt: Instant?,
  val grantsVersion: Long,
)

/** Who reaches one context, grouped as [PodGrantHolders] states. */
internal data class ContextHolders(
  val contextUri: String,
  val people: List<PersonHolder>,
  /** The owner's own delegations. */
  val ownerApps: List<AppHolder>,
  val services: List<ServiceHolder>,
)

/**
 * A person reaching a context. [grants] may be empty where only their [delegations] do: the
 * delegations of someone who holds no grant of their own any more.
 */
internal data class PersonHolder(
  val webId: String,
  val grants: List<ReachingGrant>,
  /** The apps acting for this person that reach the context. */
  val delegations: List<AppHolder>,
)

/**
 * An app acting for a person. [lastUsedAt] is when it last received a refresh token for them:
 * `null` for an app that never held one, or whose rows have expired.
 */
internal data class AppHolder(
  val clientId: String,
  /** The name it registered with, where the pod holds a registration naming it. */
  val name: String?,
  val grants: List<ReachingGrant>,
  val lastUsedAt: Instant?,
)

/**
 * A service client acting as itself. [lastUsedAt] is when it last received a token;
 * [grantsVersion] is the version a withdrawal names in `If-Match`.
 */
internal data class ServiceHolder(
  val clientId: String,
  val name: String?,
  val grants: List<ReachingGrant>,
  val lastUsedAt: Instant?,
  val grantsVersion: Long,
)

/** How many of each group reach one context. */
internal data class ContextHolderCounts(
  val contextUri: String,
  val people: Int,
  val ownerApps: Int,
  val services: Int,
)
