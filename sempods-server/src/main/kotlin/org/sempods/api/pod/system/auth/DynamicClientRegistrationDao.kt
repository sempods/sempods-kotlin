package org.sempods.api.pod.system.auth

import com.google.inject.Inject
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import org.sempods.SempodsCollections
import org.sempods.commons.mongo.getInstant
import org.sempods.commons.mongo.getStringList
import org.sempods.commons.mongo.getStringSet
import org.sempods.commons.mongo.putInstant
import org.sempods.commons.mongo.putNotNull
import org.sempods.commons.mongo.putStrings
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant

/**
 * Persistence for RFC 7591 Dynamic Client Registrations. The DAO itself is
 * insert-only at the row level — `create()` always inserts and never updates —
 * but [DynamicClientStore.register] runs a fingerprint dedup via
 * [findByFingerprint] before calling here, so re-registration of the same
 * logical client returns the existing row's `clientId` instead of producing a
 * duplicate. The one mutating operation is [touchLastAuthorized] — the sweep it
 * was written for is the TODO below.
 *
 * `findByClientId()` serves the hot-path `/authorize` lookup. Richer analysis
 * queries belong to Stage 2 and are not added pre-emptively.
 *
 * **On the MongoDB driver, mapped by hand** — see `sempods-commons-mongo/docs/document-contract.md`. The widest row
 * in the schema, and the only one carrying a nested body: [DynamicClientRegistrationDbo.rawRequest]
 * is stored verbatim, and omitted entirely when empty.
 */
// TODO: this is the pod server's second unbounded collection, and the one `oauth.serviceAuditLog`
//  no longer is. `/register` is pre-auth (RFC 7591, which MCP clients need) and unthrottled, so an
//  anonymous caller varying clientName/userAgent/redirectUris writes a row per request — the
//  fingerprint dedup absorbs a real client's re-register loop and nothing else. A TTL cannot fix
//  it the way it fixed the audit log: a registration has no write-time deadline, since a live
//  client's row must survive as long as it authorizes. It needs `lastAuthorizedAt` — already
//  written for this, and read by nothing — plus what to do with the grants that hang off a swept
//  clientId. `sempods-mcp`'s DcrClientDao carries the same note for its own copy, where the risk
//  is recorded as accepted in `sempods-mcp/docs/multi-tenancy-review.md` (M6.4).
class DynamicClientRegistrationDao internal constructor(db: MongoDatabase, collectionName: String) {

  /**
   * The production constructor — the one collection this DAO exists for. The name is a parameter
   * only so that a test can point an instance at a collection of its own, for the reason
   * `OAuthSigningKeyDao` states.
   */
  @Inject
  internal constructor(db: MongoDatabase) : this(db, SempodsCollections.OAUTH_CLIENT_REGISTRATIONS)

  private val registrations = db.getCollection(collectionName)

  init {
    // The five indexes `@Indexes` declared, with the same options — measured against the running
    // database, which carries `registeredForPodId_1_clientId_1` (unique),
    // `softwareId_1_registeredAt_1`, `registeredForPodId_1_registeredAt_1`, `registeredAt_1` and
    // `registeredForPodId_1_fingerprint_1` (partial on `fingerprint` existing). `createIndex`
    // throws `IndexOptionsConflict` against an existing index whose options differ, and that
    // failure lands at boot rather than at the first query, so the partial filter is reproduced
    // exactly rather than equivalently.
    registrations.createIndex(
      Indexes.ascending(
        DynamicClientRegistrationDboFields.registeredForPodId,
        DynamicClientRegistrationDboFields.clientId,
      ),
      IndexOptions().unique(true),
    )
    registrations.createIndex(
      Indexes.ascending(
        DynamicClientRegistrationDboFields.softwareId,
        DynamicClientRegistrationDboFields.registeredAt,
      ),
    )
    registrations.createIndex(
      Indexes.ascending(
        DynamicClientRegistrationDboFields.registeredForPodId,
        DynamicClientRegistrationDboFields.registeredAt,
      ),
    )
    registrations.createIndex(Indexes.ascending(DynamicClientRegistrationDboFields.registeredAt))
    // Partial because most rows predate the dedup change and carry no fingerprint at all; indexing
    // them would say nothing and the lookup never asks for them.
    registrations.createIndex(
      Indexes.ascending(
        DynamicClientRegistrationDboFields.registeredForPodId,
        DynamicClientRegistrationDboFields.fingerprint,
      ),
      IndexOptions().partialFilterExpression(
        Filters.exists(DynamicClientRegistrationDboFields.fingerprint, true),
      ),
    )
  }

  internal fun create(
    clientId: String,
    registeredForPodId: ObjectId,
    registeredForPodName: String,
    redirectUris: Set<String>,
    clientName: String?,
    clientUri: String?,
    logoUri: String?,
    softwareId: String?,
    softwareVersion: String?,
    contacts: List<String>,
    tosUri: String?,
    policyUri: String?,
    rawRequest: Map<String, Any?>,
    remoteAddr: String? = null,
    userAgent: String? = null,
    fingerprint: String? = null,
  ): DynamicClientRegistrationDbo {
    // The id is minted here rather than read off the write: `datastore.save()` wrote the generated
    // `_id` back into the instance it was handed and `insertOne` does not, so a caller reading it
    // off the returned row would get `null`.
    val dbo = DynamicClientRegistrationDbo(
      id = ObjectId(),
      clientId = clientId,
      registeredForPodId = registeredForPodId,
      registeredForPodName = registeredForPodName,
      registeredAt = Instant.now(),
      redirectUris = redirectUris,
      clientName = clientName,
      clientUri = clientUri,
      logoUri = logoUri,
      softwareId = softwareId,
      softwareVersion = softwareVersion,
      contacts = contacts,
      tosUri = tosUri,
      policyUri = policyUri,
      rawRequest = rawRequest,
      remoteAddr = remoteAddr,
      userAgent = userAgent,
      fingerprint = fingerprint,
    )
    registrations.insertOne(dbo.toDocument())
    return dbo
  }

  /**
   * Liveness touch. Updates [DynamicClientRegistrationDbo.lastAuthorizedAt] on the row
   * matching `(podId, clientId)`. Called from the `/token` handler, so the timestamp
   * reflects the most recent completed OAuth flow for this client.
   *
   * Returns `true` if a row matched, `false` if not (e.g. a `did:web` client that has no
   * DCR row). A false return is not an error — the caller is expected to treat the
   * touch as best-effort.
   *
   * `updateOne`, because `MorphiaDao.updateFields` issued `upsert(false)` without `multi` — and
   * `(registeredForPodId, clientId)` is unique, so there is never a second row to reach.
   */
  internal fun touchLastAuthorized(podId: ObjectId, clientId: String, at: Instant = Instant.now()): Boolean =
    registrations.updateOne(
      keyFilter(podId, clientId),
      Updates.set(DynamicClientRegistrationDboFields.lastAuthorizedAt, at),
    ).modifiedCount > 0L

  /**
   * Pod-scoped fingerprint lookup. Returns the most recent matching row so callers can
   * reuse a previously-issued clientId on re-registration.
   */
  internal fun findByFingerprint(podId: ObjectId, fingerprint: String): DynamicClientRegistrationDbo? =
    findNewest(
      Filters.and(
        Filters.eq(DynamicClientRegistrationDboFields.registeredForPodId, podId),
        Filters.eq(DynamicClientRegistrationDboFields.fingerprint, fingerprint),
      ),
    )

  /**
   * Hard-deletes every DCR row for the given pod. Used by the pod-cascade delete path:
   * after the pod is gone, its clientIds (which are pod-scoped by index) have nothing
   * to refer to. Returns the number of rows removed.
   *
   * `deleteMany`, because Morphia's `deleteAll()` is `DeleteOptions().multi(true)`: a `deleteOne`
   * carried over here would leave all but one registration of a deleted pod behind.
   */
  internal fun deleteByPod(podId: ObjectId): Long =
    registrations.deleteMany(
      Filters.eq(DynamicClientRegistrationDboFields.registeredForPodId, podId),
    ).deletedCount

  /**
   * Lookup is always pod-scoped — a clientId only exists within its issuing pod, even
   * though the random string would be globally unique. Refusing to resolve a clientId
   * at a foreign pod prevents accidental cross-pod use.
   */
  internal fun findByClientId(podId: ObjectId, clientId: String): DynamicClientRegistrationDbo? =
    findNewest(keyFilter(podId, clientId))

  /**
   * The newest row matching [filter].
   *
   * Both lookups sort by `registeredAt` descending and take one, as they did under Morphia. For
   * `findByClientId` the unique index means there is only ever one row — the sort is what makes
   * the two read paths answer the same way, and it costs nothing the index does not already give.
   */
  private fun findNewest(filter: Bson): DynamicClientRegistrationDbo? =
    registrations.find(filter)
      .sort(Sorts.descending(DynamicClientRegistrationDboFields.registeredAt))
      .limit(1)
      .first()
      ?.toDbo()

  private companion object {

    private fun keyFilter(podId: ObjectId, clientId: String): Bson = Filters.and(
      Filters.eq(DynamicClientRegistrationDboFields.registeredForPodId, podId),
      Filters.eq(DynamicClientRegistrationDboFields.clientId, clientId),
    )

    /**
     * The field order Morphia wrote, kept because a row that differs from its neighbours only in
     * order reads differently in a dump. Pinned by that wire-format test.
     */
    private fun DynamicClientRegistrationDbo.toDocument(): Document = Document()
      .putNotNull(DynamicClientRegistrationDboFields.id, id)
      .putNotNull(DynamicClientRegistrationDboFields.clientId, clientId)
      .putNotNull(DynamicClientRegistrationDboFields.registeredForPodId, registeredForPodId)
      .putNotNull(DynamicClientRegistrationDboFields.registeredForPodName, registeredForPodName)
      .putInstant(DynamicClientRegistrationDboFields.registeredAt, registeredAt)
      .putStrings(DynamicClientRegistrationDboFields.redirectUris, redirectUris)
      .putNotNull(DynamicClientRegistrationDboFields.clientName, clientName)
      .putNotNull(DynamicClientRegistrationDboFields.clientUri, clientUri)
      .putNotNull(DynamicClientRegistrationDboFields.logoUri, logoUri)
      .putNotNull(DynamicClientRegistrationDboFields.softwareId, softwareId)
      .putNotNull(DynamicClientRegistrationDboFields.softwareVersion, softwareVersion)
      .putStrings(DynamicClientRegistrationDboFields.contacts, contacts)
      .putNotNull(DynamicClientRegistrationDboFields.tosUri, tosUri)
      .putNotNull(DynamicClientRegistrationDboFields.policyUri, policyUri)
      // The verbatim body, nested. Omitted when empty, the way an empty collection is — writing
      // `{}` would be the one field where the two writers disagree, and this is the only record of
      // what the client actually sent.
      .putNotNull(
        DynamicClientRegistrationDboFields.rawRequest,
        rawRequest.takeIf { it.isNotEmpty() }?.let(::Document),
      )
      .putNotNull(DynamicClientRegistrationDboFields.remoteAddr, remoteAddr)
      .putNotNull(DynamicClientRegistrationDboFields.userAgent, userAgent)
      .putNotNull(DynamicClientRegistrationDboFields.fingerprint, fingerprint)
      .putInstant(DynamicClientRegistrationDboFields.lastAuthorizedAt, lastAuthorizedAt)
      .putNotNull(DynamicClientRegistrationDboFields.schemaVersion, schemaVersion)

    private fun Document.toDbo(): DynamicClientRegistrationDbo = DynamicClientRegistrationDbo(
      id = getObjectId(DynamicClientRegistrationDboFields.id),
      clientId = getString(DynamicClientRegistrationDboFields.clientId),
      registeredForPodId = getObjectId(DynamicClientRegistrationDboFields.registeredForPodId),
      registeredForPodName = getString(DynamicClientRegistrationDboFields.registeredForPodName),
      // Non-null in the entity since the collection existed. Failing loudly beats defaulting to
      // `now`, which would sort a corrupt row to the top of both lookups — they take the newest.
      registeredAt = checkNotNull(getInstant(DynamicClientRegistrationDboFields.registeredAt)) {
        "registration without registeredAt: ${getString(DynamicClientRegistrationDboFields.clientId)}"
      },
      redirectUris = getStringSet(DynamicClientRegistrationDboFields.redirectUris),
      clientName = getString(DynamicClientRegistrationDboFields.clientName),
      clientUri = getString(DynamicClientRegistrationDboFields.clientUri),
      logoUri = getString(DynamicClientRegistrationDboFields.logoUri),
      softwareId = getString(DynamicClientRegistrationDboFields.softwareId),
      softwareVersion = getString(DynamicClientRegistrationDboFields.softwareVersion),
      // Empty rather than absent: the field is non-nullable in the entity and missing on most
      // rows, so a reader that did not default here would fail on every registration that sent no
      // contact address.
      contacts = getStringList(DynamicClientRegistrationDboFields.contacts),
      tosUri = getString(DynamicClientRegistrationDboFields.tosUri),
      policyUri = getString(DynamicClientRegistrationDboFields.policyUri),
      rawRequest = get(DynamicClientRegistrationDboFields.rawRequest, Document::class.java)
        ?: emptyMap<String, Any?>(),
      remoteAddr = getString(DynamicClientRegistrationDboFields.remoteAddr),
      userAgent = getString(DynamicClientRegistrationDboFields.userAgent),
      fingerprint = getString(DynamicClientRegistrationDboFields.fingerprint),
      lastAuthorizedAt = getInstant(DynamicClientRegistrationDboFields.lastAuthorizedAt),
      // Defaulted for the rows written before the field existed, which is what it is for.
      schemaVersion = getInteger(DynamicClientRegistrationDboFields.schemaVersion, 1),
    )
  }
}
