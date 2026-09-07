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
 * One definition, because `DynamicClientRegistrationDao` and `DcrFingerprintUniqueness` both build
 * it: two spellings would conflict at every boot.
 *
 * Partial because rows predating the dedup carry no fingerprint, and because it is what lets a
 * duplicate be retired by unsetting the field rather than by deleting a row grants hang off.
 */
internal object DcrFingerprintIndex {

  val keys: Bson = Indexes.ascending(
    DynamicClientRegistrationDboFields.registeredForPodId,
    DynamicClientRegistrationDboFields.fingerprint,
  )

  /** Named, where the other four take MongoDB's default: [replaceOn] needs a handle of its own. */
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
   * The order is what makes concurrent boots safe, and the name is what allows the order. MongoDB
   * keeps two indexes over one key pattern when their names differ, so [createOn] succeeds beside
   * the predecessor instead of conflicting with it: the constraint is in place before anything is
   * dropped, and a drop that names the predecessor cannot take the index another replica just
   * built. Both matter because a gap is not self-correcting — two `/register` calls landing in one
   * each insert and each **return** an id, and the sweep cannot take back what was handed out.
   */
  fun replaceOn(registrations: MongoCollection<Document>): Boolean {
    createOn(registrations)
    val stale = predecessorName(registrations) ?: return false
    try {
      registrations.dropIndex(stale)
    } catch (alreadyGone: MongoCommandException) {
      // 27 = IndexNotFound: another replica dropped it first. Not a failure — the constraint is
      // already built, and this is only the tidying after it.
      if (alreadyGone.errorCode != INDEX_NOT_FOUND) throw alreadyGone
    }
    return true
  }

  /** An index over this key pattern that is not the one built here — `null` is the ordinary answer. */
  private fun predecessorName(registrations: MongoCollection<Document>): String? =
    registrations.listIndexes().firstOrNull { index ->
      index.get("key", Document::class.java)?.keys?.toList() == KEY_FIELDS &&
        index.getString("name") != UNIQUE_NAME
    }?.getString("name")

  /** Fresh per call: `IndexOptions` is mutable. */
  private fun options(): IndexOptions = IndexOptions()
    .name(UNIQUE_NAME)
    .unique(true)
    .partialFilterExpression(
      Filters.exists(DynamicClientRegistrationDboFields.fingerprint, true),
    )

  private const val INDEX_NOT_FOUND = 27
}
