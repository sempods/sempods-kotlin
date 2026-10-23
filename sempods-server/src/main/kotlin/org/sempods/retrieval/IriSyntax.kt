package org.sempods.retrieval

import org.eclipse.rdf4j.common.net.ParsedIRI
import org.eclipse.rdf4j.model.IRI
import java.net.URISyntaxException

/**
 * Guard for a caller-supplied IRI that is about to be embedded as a SPARQL `IRIREF` (`<…>`) in
 * query text.
 *
 * `SimpleValueFactory.createIRI` only requires a colon, so an [IRI] can carry characters that no
 * RFC 3987 IRI may contain. [isWellFormed] parses the string with RDF4J's strict `ParsedIRI`
 * constructor and requires an absolute IRI. That rejects:
 *
 * - the `IRIREF` break-out characters `<` `>` `"` `{` `}` `|` `^` `` ` `` `\`, space and controls;
 * - every code point outside RFC 3987 `ucschar` — lone surrogates, non-characters, the specials
 *   `U+FFF0`–`U+FFFD`, the tags `U+E0000`–`U+E0FFF`;
 * - private-use code points, except in the query component, where RFC 3987 admits them as `iprivate`;
 * - a malformed structure or percent-encoding, and a relative reference.
 *
 * The break-out characters are what make this an injection guard. Everything else is RFC 3987
 * hygiene that RDF4J's value factory does not apply. An IRI passed to the RDF4J store API or bound
 * into a parsed query needs no guard.
 */
object IriSyntax {

  /** Whether [iri] is an absolute RFC 3987 IRI, and so can be wrapped as a SPARQL `IRIREF` (`<…>`). */
  fun isWellFormed(iri: IRI): Boolean =
    try {
      ParsedIRI(iri.stringValue()).isAbsolute
    } catch (_: URISyntaxException) {
      false
    } catch (_: IllegalArgumentException) {
      // ParsedIRI reads an out-of-range port with Integer.parseInt and lets the NumberFormatException out.
      false
    }
}
