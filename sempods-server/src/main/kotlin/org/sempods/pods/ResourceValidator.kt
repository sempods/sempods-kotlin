package org.sempods.pods

import org.sempods.commons.utils.HashUtil
import org.sempods.rdf.RdfWriterUtil
import org.eclipse.rdf4j.model.Model

/**
 * Strong, store-derived ETag validator for a resource: a stable content hash over the resource's
 * own-subject statements across all contexts. Replaced the MongoDB `dateModified` timestamp as the
 * ETag source when reads moved to the store.
 *
 * Deterministic because blank nodes are forbidden — every statement has a stable N-Quads form, so
 * sorting the per-statement lines yields a canonical serialization: identical content always hashes
 * to the same value, regardless of statement iteration order. A change to *any* statement of the
 * resource (any predicate, any context) changes the hash — the same resource-snapshot granularity
 * the timestamp had, but a true content validator (no spurious bumps, no missing bumps).
 */
object ResourceValidator {

  fun compute(model: Model): String {
    val canonical = RdfWriterUtil.writeNQuads(model)
      .lineSequence()
      .filter { it.isNotBlank() }
      .sorted()
      .joinToString("\n")
    return HashUtil.sha256Hex(canonical).take(16)
  }
}
