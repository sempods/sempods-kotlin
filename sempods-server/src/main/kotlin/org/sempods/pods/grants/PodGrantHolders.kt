package org.sempods.pods.grants

import com.google.inject.Inject
import org.sempods.commons.identity.WebIdUriDeriver
import org.sempods.pods.HostedPod
import org.sempods.pods.contexts.persist.PodContextsDao
import org.sempods.pods.grants.persist.PodGrantDbo
import org.sempods.pods.grants.persist.PodGrantsDao
import org.sempods.pods.grants.persist.PodWebIdGrantsDao
import org.sempods.pods.mongo.persist.objectId
import org.sempods.pods.oauth.PodRefreshTokenStore
import org.sempods.pods.oauth.serviceclients.PodServiceClientStore
import java.time.Instant

/**
 * The grants held on the contexts of a pod, as its owner sees them: the people holding grants, the
 * apps acting for each of them, the owner's own apps, and the services acting as themselves.
 *
 * **The person is the unit.** A delegation is keyed by app and WebID and is bounded by that
 * person's grants at consent ([PodGrantsFacade]), so it is listed under the person and goes with
 * them. One person may hold grants under several WebIDs: those a sign-in recognised together, which
 * a delegation records in [PodGrantDbo.subjectUris], and the derivable spellings of each. They are
 * one person here. The owner holds no WebID grants — their authority is implicit — so their
 * delegations are a group of their own: every delegation whose person shares a URI with the owner,
 * including one given under a WebID a later sign-in linked to the owner. Grants such a WebID holds
 * are not listed, as the owner reaches everything anyway.
 *
 * Only explicit grants are listed. A public context is readable by anyone without one, and nothing
 * here changes that.
 *
 * Read in one pass: the registry, both grant levels, the registrations and the refresh rows, each
 * once, however many contexts the pod has. [snapshot] answers every context from that pass. Only
 * registered contexts are answered; the context-deletion sweep removes grants naming others.
 */
class PodGrantHolders @Inject internal constructor(
  private val podContextsDao: PodContextsDao,
  private val podWebIdGrantsDao: PodWebIdGrantsDao,
  private val podGrantsDao: PodGrantsDao,
  private val podServiceClientStore: PodServiceClientStore,
  private val refreshTokenStore: PodRefreshTokenStore,
  private val podScopeValidator: PodScopeValidator,
  private val podContextPermissionResolver: PodContextPermissionResolver,
  private val podGrantsFacade: PodGrantsFacade,
  private val webIdUriDeriver: WebIdUriDeriver,
) {

  /**
   * Everyone holding a grant on [pod], ready to be asked about any registered context.
   *
   * @param appName what to call an app acting for a person, asked once per app; `null` for none.
   *   Only a listing shows names, so the counts and the withdrawals pass nothing and look none up.
   */
  internal fun snapshot(pod: HostedPod, appName: (clientId: String) -> String? = { null }): GrantHoldersSnapshot {
    val storedPodId = pod.id.objectId()
    val contexts = podContextsDao.fetchByPod(storedPodId).map { it.contextUri }.sorted()
    val lastIssued = refreshTokenStore.lastIssuedPerConnection(pod.id)

    val people = people(pod)
    podGrantsDao.fetchGrantsByPod(storedPodId).forEach { row -> people.attach(row, rowUris(row)) }
    // Only once every alias set is joined: a WebID's earlier delegation is the owner's as soon as a
    // later one records it beside the owner's WebID.
    val owner = people.separate(podGrantsFacade.ownerUris(pod))

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
        webId = person.displayed(),
        uris = person.uris,
        grantWebIds = person.grantWebIds,
        grants = parse(pod, person.grants),
        delegations = apps(person.rows),
      )
    }
    val services = podServiceClientStore.list(pod.id).map { registration ->
      HeldService(
        clientId = registration.clientId,
        name = registration.label?.takeIf { it.isNotBlank() },
        grants = parse(pod, registration.scopes),
        lastUsedAt = registration.lastUsedAt,
        grantsVersion = registration.grantsVersion,
        // Its registration resolves no grant while it holds the authority, for a token it obtained
        // before as well (`PodContextPermissionResolver.resolve`); after a change of owner it obtains none.
        reachesData = registration.management == null,
      )
    }
    return GrantHoldersSnapshot(
      contexts = contexts,
      people = heldPeople,
      ownerApps = apps(owner.rows),
      ownerUris = owner.uris,
      services = services,
      resolver = podContextPermissionResolver,
    )
  }

  /** The people holding WebID grants on [pod] under a WebID other than its owner's, before their delegations are attached. */
  private fun people(pod: HostedPod): People {
    val people = People()
    podWebIdGrantsDao.fetchByPod(pod.id.objectId())
      .filterNot { podGrantsFacade.isPodOwner(pod, it.webId) }
      .groupBy { it.webId }
      .forEach { (webId, grants) ->
        people.join(webIdUriDeriver.derivableAliases(webId) + webId).apply {
          grantWebIds += webId
          this.grants += grants.map { it.scope }
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

  /**
   * People being grouped. A set of URIs that shares one with a person is that person, and one that
   * shares URIs with several joins them: two WebIDs a delegation was consented under are one person
   * even where each holds grants of its own.
   */
  private class People : Iterable<Person> {
    private val people = mutableListOf<Person>()

    override fun iterator(): Iterator<Person> = people.iterator()

    fun join(uris: Set<String>): Person {
      val matching = people.filter { person -> person.uris.any(uris::contains) }
      val person = matching.firstOrNull() ?: Person().also { people += it }
      matching.drop(1).forEach { other ->
        person.absorb(other)
        people.remove(other)
      }
      person.uris += uris
      return person
    }

    /** Lists [row] under its person; a row naming nobody holding grants starts a person of its own. */
    fun attach(row: PodGrantDbo, uris: Set<String>) {
      join(uris).rows += row
    }

    /** Takes out the one person sharing a URI with [uris], joined with all of them. */
    fun separate(uris: Set<String>): Person = join(uris).also { people.remove(it) }
  }

  private class Person {
    val uris = mutableSetOf<String>()
    val grantWebIds = mutableSetOf<String>()
    val grants = mutableSetOf<String>()
    val rows = mutableListOf<PodGrantDbo>()

    fun absorb(other: Person) {
      uris += other.uris
      grantWebIds += other.grantWebIds
      grants += other.grants
      rows += other.rows
    }

    /**
     * The WebID the answer names: among the WebIDs holding grants, or for someone holding none,
     * among those their delegations are stored under, the first `http` one in string order, else
     * the first in string order. Of `https://id.example/z` and `https://id.example/a` it is the
     * second, whichever was delegated first.
     */
    fun displayed(): String =
      (grantWebIds.ifEmpty { rows.map { it.webId } }).sortedWith(compareBy({ !it.startsWith("http") }, { it })).first()
  }
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
  /** Every URI the owner's delegations name: the owner's WebID, its twins, and the WebIDs linked to it. */
  val ownerUris: Set<String>,
  private val services: List<HeldService>,
  private val resolver: PodContextPermissionResolver,
) {

  /** Who holds grants reaching [contextUri]; `null` where no such context is registered. */
  fun on(contextUri: String): ContextHolders? {
    if (contextUri !in contexts) return null
    return ContextHolders(
      contextUri = contextUri,
      people = people.mapNotNull { person ->
        val delegations = person.delegations.mapNotNull { it.on(contextUri) }
        val grants = reaching(person.grants, contextUri)
        if (grants.isEmpty() && delegations.isEmpty()) null
        else PersonHolder(person.webId, person.uris, person.grantWebIds, grants, delegations)
      }.sortedBy { it.webId },
      ownerApps = ownerApps.mapNotNull { it.on(contextUri) }.sortedBy { it.clientId },
      services = services.mapNotNull { service ->
        reaching(service.grants, contextUri).takeIf { it.isNotEmpty() }?.let { grants ->
          ServiceHolder(service.clientId, service.name, grants, service.lastUsedAt, service.grantsVersion, service.reachesData)
        }
      }.sortedBy { it.clientId },
    )
  }

  /**
   * How many of each group reach every registered context, ascending by context. A service whose
   * grants reach no data ([ServiceHolder.reachesData]) is not counted.
   */
  fun counts(): List<ContextHolderCounts> = contexts.map { contextUri ->
    val holders = checkNotNull(on(contextUri))
    ContextHolderCounts(
      contextUri = contextUri,
      people = holders.people.size,
      ownerApps = holders.ownerApps.size,
      services = holders.services.count { it.reachesData },
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
  val uris: Set<String>,
  val grantWebIds: Set<String>,
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
  val reachesData: Boolean,
)

/** Who holds grants reaching one context, grouped as [PodGrantHolders] states. */
internal data class ContextHolders(
  val contextUri: String,
  val people: List<PersonHolder>,
  /** The owner's own delegations. */
  val ownerApps: List<AppHolder>,
  val services: List<ServiceHolder>,
)

/**
 * A person reaching a context. [grants] may be empty where only their [delegations] do: the
 * delegations of someone who holds no grant reaching it, such as a former owner.
 */
internal data class PersonHolder(
  /** The WebID the answer names them by. */
  val webId: String,
  /** Every URI they are known by here; a withdrawal may name any of them. */
  val uris: Set<String>,
  /** The WebIDs their grants are stored under, which a withdrawal withdraws from. */
  val grantWebIds: Set<String>,
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
  /**
   * `false` for a service holding the management authority: its stored grants can be withdrawn, but
   * every token it obtains reaches no data, so it can do nothing on the context.
   */
  val reachesData: Boolean,
)

/** How many of each group reach one context. */
internal data class ContextHolderCounts(
  val contextUri: String,
  val people: Int,
  val ownerApps: Int,
  val services: Int,
)
