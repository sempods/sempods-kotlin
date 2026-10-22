package org.sempods.retrieval

import org.eclipse.rdf4j.model.IRI

/**
 * Guard for a caller-supplied IRI that is about to be embedded as a SPARQL `IRIREF` (`<…>`) in
 * query text. `FindRequestParser` and `FindQueryBuilder` share it.
 *
 * `SimpleValueFactory.createIRI` only requires a scheme, so an [IRI] can carry characters that no
 * RFC 3987 IRI may contain. [isWellFormed] scans the string by code point and rejects:
 *
 * - the `IRIREF` break-out characters `<` `>` `"` `{` `}` `|` `^` `` ` `` `\`;
 * - space and controls: `U+0000`–`U+0020`, `U+007F` and `U+0080`–`U+009F`;
 * - lone surrogates: `U+D800`–`U+DFFF`;
 * - private-use code points: `U+E000`–`U+F8FF`, `U+F0000`–`U+FFFFD` and `U+100000`–`U+10FFFD`;
 * - non-characters: `U+FDD0`–`U+FDEF`, and `xFFFE`/`xFFFF` in every plane.
 *
 * The first two would break out of the `IRIREF`, and RDF4J rejects them even when `\u`-escaped.
 * The other three are RFC 3987 `ucschar` hygiene: RDF4J accepts them. A valid surrogate pair counts
 * as the one supplementary character it encodes, so `U+20000` passes. RFC 3987 admits private-use
 * code points in the query component (`iprivate`); this guard does not parse components and rejects
 * them everywhere.
 *
 * This is not a general IRI validator: it checks neither scheme nor structure. Use it where a
 * caller-supplied IRI is written into SPARQL text. An IRI passed to the RDF4J store API or bound
 * into a parsed query needs no guard.
 */
object IriSyntax {

  // '<' '>' '"' '{' '}' '|' '^' '`' '\\'
  private val ILLEGAL = setOf(0x3C, 0x3E, 0x22, 0x7B, 0x7D, 0x7C, 0x5E, 0x60, 0x5C)

  /** Whether [iri] can be wrapped as a SPARQL `IRIREF` (`<…>`). `false` if it holds a rejected code point. */
  fun isWellFormed(iri: IRI): Boolean = iri.stringValue().codePoints().noneMatch(::isRejected)

  private fun isRejected(codePoint: Int): Boolean =
    codePoint <= 0x20 ||
      codePoint == 0x7F ||
      codePoint in 0x80..0x9F ||
      codePoint in ILLEGAL ||
      // Only an unpaired surrogate reaches here; codePoints() decodes a valid pair to one code point.
      codePoint in 0xD800..0xDFFF ||
      isPrivateUse(codePoint) ||
      isNonCharacter(codePoint)

  private fun isPrivateUse(codePoint: Int): Boolean =
    codePoint in 0xE000..0xF8FF || codePoint in 0xF0000..0xFFFFD || codePoint in 0x100000..0x10FFFD

  private fun isNonCharacter(codePoint: Int): Boolean =
    codePoint in 0xFDD0..0xFDEF || (codePoint and 0xFFFE) == 0xFFFE
}
