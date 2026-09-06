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
 * creates it and `DcrFingerprintUniqueness` replaces the one an older build left; if those two
 * spelled the options out separately, a difference between them would make one of them throw
 * `IndexOptionsConflict` against the other's index — at boot, on every boot.
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

  /** Builds it, or does nothing where it already stands with exactly these options. */
  fun createOn(registrations: MongoCollection<Document>) {
    registrations.createIndex(keys, options())
  }

  /**
   * Builds it, dropping whatever incompatible index holds the key pattern first. Answers whether
   * it had to.
   *
   * **Driven by the conflict rather than by a prior read**, and that is the whole difference: the
   * pod server runs more than one replica (`OAuthSigningKeyDao.createInitial` exists for the same
   * reason), so two boots can be in here at once. A version that listed the indexes, remembered
   * the old one's name and dropped it later would drop by a decision taken before the other
   * replica acted — and since MongoDB derives both names from the same key pattern, that name also
   * names the *new* unique index, so the second replica would delete the constraint the first had
   * just built and leave it serving `/register` without one. Here the drop only ever happens
   * against an index `createIndex` has just refused, and a create follows it immediately.
   *
   * What is left is one round trip: between a replica's drop and its create, a replica that is
   * already serving has no unique index. Closing that needs a lock across boots, which this
   * repository has no mechanism for and does not want one invented for a data change
   * (`AGENTS.md` §"What this repository deliberately does not have"). The residual is self-healing
   * — a duplicate written in that window is what the next boot's unset pass removes.
   */
  fun replaceOn(registrations: MongoCollection<Document>): Boolean = try {
    createOn(registrations)
    false
  } catch (e: MongoCommandException) {
    // 85 = IndexOptionsConflict, 86 = IndexKeySpecsConflict: an index over this key pattern with
    // options that are not these. Nothing else here builds one, so it is the non-unique
    // predecessor. Anything else is a real failure and propagates.
    if (e.errorCode != INDEX_OPTIONS_CONFLICT && e.errorCode != INDEX_KEY_SPECS_CONFLICT) throw e
    registrations.dropIndex(keys)
    createOn(registrations)
    true
  }

  /** Fresh per call: `IndexOptions` is mutable, so a shared instance is a shared surprise. */
  private fun options(): IndexOptions = IndexOptions()
    .unique(true)
    .partialFilterExpression(
      Filters.exists(DynamicClientRegistrationDboFields.fingerprint, true),
    )

  private const val INDEX_OPTIONS_CONFLICT = 85
  private const val INDEX_KEY_SPECS_CONFLICT = 86
}
