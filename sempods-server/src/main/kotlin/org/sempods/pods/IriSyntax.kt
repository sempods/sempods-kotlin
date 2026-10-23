package org.sempods.pods

import org.eclipse.rdf4j.common.net.ParsedIRI
import org.eclipse.rdf4j.model.IRI
import java.net.URISyntaxException

/**
 * Whether a string is an absolute RFC 3987 IRI — what every IRI a pod stores must be, and what may
 * be embedded as a SPARQL `IRIREF` (`<…>`) in query text.
 *
 * `SimpleValueFactory.createIRI` only requires a colon, RDF4J's parsers let some IRIs through
 * unchecked (a JSON-LD literal datatype, a relative reference in N-Quads), and `java.net.URI` admits
 * any non-ASCII character, so an [IRI] or a [java.net.URI] can carry characters that no RFC 3987
 * IRI may contain. This guard parses the string with RDF4J's strict `ParsedIRI` constructor
 * and requires an absolute IRI — the rule `Values.iri` applies too, by throwing. That rejects:
 *
 * - the `IRIREF` break-out characters `<` `>` `"` `{` `}` `|` `^` `` ` `` `\`, space and controls;
 * - every code point outside RFC 3987 `ucschar` — lone surrogates, non-characters, the specials
 *   `U+FFF0`–`U+FFFD`, the tags `U+E0000`–`U+E0FFF`;
 * - private-use code points, except in the query component, where RFC 3987 admits them as `iprivate`;
 * - a malformed structure or percent-encoding, and a relative reference.
 *
 * The break-out characters are what make this an injection guard; everything else is RFC 3987
 * hygiene. [ResourceBoundary] applies it to everything a pod stores, and the routes answer `400`
 * with [rejectionReason] before that.
 */
object IriSyntax {

  /** Why [iri] is not an absolute RFC 3987 IRI, as a message naming it — or null when it is one. */
  fun rejectionReason(iri: String): String? {
    val detail = try {
      if (ParsedIRI(iri).isAbsolute) return null
      "it is a relative reference"
    } catch (e: URISyntaxException) {
      e.reason
    } catch (_: IllegalArgumentException) {
      // ParsedIRI reads an out-of-range port with Integer.parseInt and lets the NumberFormatException out.
      "its port is out of range"
    }
    return "'$iri' is not an absolute RFC 3987 IRI: $detail"
  }

  /** Whether [iri] is an absolute RFC 3987 IRI. */
  fun isWellFormed(iri: String): Boolean = rejectionReason(iri) == null

  /** Whether [iri] is an absolute RFC 3987 IRI, and so can be wrapped as a SPARQL `IRIREF` (`<…>`). */
  fun isWellFormed(iri: IRI): Boolean = isWellFormed(iri.stringValue())
}
