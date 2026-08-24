package org.sempods.commons.mongo

import com.mongodb.ErrorCategory
import com.mongodb.MongoWriteException

/**
 * True when this write failed because it violated a unique index — the "someone else inserted the
 * same key first" case that a read-then-insert has to treat as success rather than as an error.
 *
 * Worth an explicit check: [MongoWriteException] also covers write-concern failures, document
 * validation and connectivity problems, and a blanket `catch` would report those as a benign race,
 * hiding real persistence faults behind an idempotent-looking response.
 */
fun MongoWriteException.isDuplicateKey(): Boolean =
  ErrorCategory.fromErrorCode(error.code) == ErrorCategory.DUPLICATE_KEY
