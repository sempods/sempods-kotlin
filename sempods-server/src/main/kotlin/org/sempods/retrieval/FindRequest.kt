package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI

/**
 * A parsed `find` request. The endpoint owns input parsing (tokenization, IRI/limit parsing,
 * the empty-`text` rejection); the retrieval layer works on this validated shape.
 *
 * @property tokens  whitespace-split query tokens **as the caller wrote them**; never empty. A hit
 *                   must have a single literal that contains *all* tokens, compared
 *                   case-insensitively — an adapter normalizes at the point of comparison and must
 *                   not assume these arrive pre-folded. Casing is preserved because it is signal for
 *                   adapters that forward the query to an external engine (entity names, acronyms),
 *                   and case cannot be recovered once discarded.
 * @property types   optional `rdf:type` constraint, OR-combined. Empty = no type constraint
 *                   (pure text search). A return-type constraint, not a match-scope.
 * @property limit   max distinct hit resources; already clamped to `1..100` by the endpoint.
 */
data class FindRequest(
  val tokens: List<String>,
  val types: Set<IRI>,
  val limit: Int,
)
