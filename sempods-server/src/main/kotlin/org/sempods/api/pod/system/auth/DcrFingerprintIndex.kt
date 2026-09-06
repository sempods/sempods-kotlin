package org.sempods.api.pod.system.auth

import com.mongodb.MongoCommandException
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import org.bson.Document
import org.bson.conversions.Bson

/**
 * The index that makes the DCR dedup a constraint rather than a lookup:
 * `(registeredForPodId, fingerprint)`, unique, partial on the fingerprint existing.
 *
 * One definition, because two would be the failure it prevents. `DynamicClientRegistrationDao`
 * creates it and `DcrFingerprintUniqueness` builds it and clears away the one an older build left;
 * if those two spelled the options out separately, a difference between them would make one of them
 * throw `IndexOptionsConflict` against the other's index — at boot, on every boot.
 *
 * Partial because most rows predate the dedup and carry no fingerprint at all: indexing them would
 * say nothing, the lookup never asks for them, and it is what lets a duplicate be retired by
 * unsetting the field rather than by deleting a row somebody's grants hang off.
 */
internal object DcrFingerprintIndex {

  val keys: Bson = Indexes.ascending(
    DynamicClientRegistrationDboFields.registeredForPodId,
    DynamicClientRegistrationDboFields.fingerprint,
  )

  /**
   * Named, where every other index in this collection takes MongoDB's default. The name is not
   * decoration: it is the handle [replaceOn] drops by, and it is what stops one replica's drop from
   * removing the index another replica has already built in its place — see there.
   */
  const val UNIQUE_NAME = "registeredForPodId_1_fingerprint_1_unique"

  private val KEY_FIELDS = listOf(
    DynamicClientRegistrationDboFields.registeredForPodId,
    DynamicClientRegistrationDboFields.fingerprint,
  )

  /** Builds it, or does nothing where it already stands with exactly these options. */
  fun createOn(registrations: MongoCollection<Document>) {
    registrations.createIndex(keys, options())
  }

  /**
   * Builds it and clears the predecessor away. Answers whether there was one.
   *
   * **The name is what makes this safe under concurrent boots**, and it does it twice. The pod
   * server runs more than one replica (`OAuthSigningKeyDao.createInitial` exists for the same
   * reason), so two boots can be in here at once, and being refused is not a decision either can
   * act on later: both can be refused before either drops.
   *
   * A name of its own removes the refusal altogether. MongoDB keeps two indexes over one key
   * pattern when their names and options differ, so [createOn] simply succeeds beside the
   * predecessor — and running it twice is a no-op, because the second replica asks for exactly the
   * index the first built. **The constraint is therefore in place before anything is dropped**,
   * where a replace would have had to open a gap to make room for it.
   *
   * And the drop can then only ever name the predecessor. By key pattern the old index and the new
   * one are the same handle, so a replica dropping that way would delete the constraint another had
   * just built; by name they are two things, and a drop whose target another replica already
   * removed finds nothing.
   *
   * Both halves matter because the gap is not self-correcting. Two `/register` calls landing while
   * no unique index stands both insert and both **return** their own `client_id`, and the sweep
   * does not undo that — it unsets the older row's fingerprint so the lookup answers one of them,
   * while the id it already handed out keeps its own grants. The database ends consistent and the
   * client stays split in two, which is the whole of what this exists to prevent.
   */
  fun replaceOn(registrations: MongoCollection<Document>): Boolean {
    createOn(registrations)
    val stale = predecessorName(registrations) ?: return false
    try {
      registrations.dropIndex(stale)
    } catch (alreadyGone: MongoCommandException) {
      // 27 = IndexNotFound: the other replica dropped it between this one's read and its drop.
      // Not a failure — the constraint is already built, and this is the tidying after it. Left
      // unhandled it would have `SempodsUpdater` log a failed migration at SEVERE on a boot where
      // everything worked, which is the one signal an operator has.
      if (alreadyGone.errorCode != INDEX_NOT_FOUND) throw alreadyGone
    }
    return true
  }

  /**
   * The name of an index over this key pattern that is not the one built here, or `null` where the
   * only one is. Null is the ordinary answer: on a database that never had the predecessor, and on
   * the second replica of a boot where the first has already cleared it.
   */
  private fun predecessorName(registrations: MongoCollection<Document>): String? =
    registrations.listIndexes().firstOrNull { index ->
      index.get("key", Document::class.java)?.keys?.toList() == KEY_FIELDS &&
        index.getString("name") != UNIQUE_NAME
    }?.getString("name")

  /** Fresh per call: `IndexOptions` is mutable, so a shared instance is a shared surprise. */
  private fun options(): IndexOptions = IndexOptions()
    .name(UNIQUE_NAME)
    .unique(true)
    .partialFilterExpression(
      Filters.exists(DynamicClientRegistrationDboFields.fingerprint, true),
    )

  private const val INDEX_NOT_FOUND = 27
}
