package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.impl.SimpleValueFactory

/**
 * Parses + validates raw `find` inputs into a [FindRequest], shared by the REST endpoint and the
 * MCP tool so the two surfaces cannot drift. Signals bad input surface-neutrally via
 * [IllegalArgumentException]; each surface maps that to its own error shape (REST 400 / MCP tool
 * error).
 */
object FindRequestParser {

  const val DEFAULT_LIMIT = 10
  const val MAX_LIMIT = 100

  private val vf = SimpleValueFactory.getInstance()
  private val WHITESPACE = Regex("\\s+")

  /**
   * @param text  the query text — whitespace-split into tokens, **verbatim**: the caller's casing
   *   is preserved, because it is signal an adapter may need (entity names, acronyms). Matching is
   *   case-insensitive, but that is each adapter's job at the point of comparison, not a
   *   normalization baked into the shared [FindRequest]. **Required**: absent / whitespace-only →
   *   [IllegalArgumentException] (never a filter-less match-all query).
   * @param typeIris optional `rdf:type` constraint IRIs (OR-combined); blank entries ignored;
   *   a syntactically invalid IRI → [IllegalArgumentException].
   * @param limit optional; defaulted to [DEFAULT_LIMIT] and clamped to `1..`[MAX_LIMIT].
   */
  fun parse(text: String?, typeIris: List<String>, limit: Int?): FindRequest {
    val tokens = text?.trim()
      ?.split(WHITESPACE)
      ?.filter { it.isNotEmpty() }
      ?: emptyList()
    require(tokens.isNotEmpty()) { "query parameter 'text' must not be empty" }

    val types: Set<IRI> = buildSet {
      typeIris.forEach { raw ->
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return@forEach
        val iri = try {
          vf.createIRI(trimmed)
        } catch (_: IllegalArgumentException) {
          throw IllegalArgumentException("invalid 'type' IRI: $trimmed")
        }
        // createIRI is lax; reject IRIs with characters no RFC 3987 IRI may contain (also the
        // SPARQL IRIREF break-out chars) so a malicious type can never reach query construction.
        require(IriSyntax.isWellFormed(iri)) { "invalid 'type' IRI: $trimmed" }
        add(iri)
      }
    }

    val clampedLimit = (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
    return FindRequest(tokens = tokens, types = types, limit = clampedLimit)
  }
}
