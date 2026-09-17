package org.sempods.pods

import org.sempods.commons.utils.HashUtil
import org.sempods.rdf.RdfWriterUtil
import org.eclipse.rdf4j.model.Model

/**
 * A stable content hash over a model's statements — the value behind the strong ETags of resource,
 * slot and context-registry representations.
 *
 * Deterministic because blank nodes are forbidden — every statement has a stable N-Quads form, so
 * sorting the per-statement lines yields a canonical serialization: identical content always hashes
 * to the same value, regardless of statement iteration order. The context is part of each line, so
 * moving a statement to another context changes the hash.
 */
object ResourceValidator {

  /**
   * The hash of [model], and of [scope] when the tag depends on more than the statements — such as
   * the contexts a read selected. An empty [scope] hashes the statements alone.
   */
  @JvmOverloads
  fun compute(model: Model, scope: String = ""): String {
    val canonical = RdfWriterUtil.writeNQuads(model)
      .lineSequence()
      .filter { it.isNotBlank() }
      .sorted()
      .joinToString("\n")
    val input = if (scope.isEmpty()) canonical else "$canonical\n$scope"
    return HashUtil.sha256Hex(input).take(16)
  }
}
