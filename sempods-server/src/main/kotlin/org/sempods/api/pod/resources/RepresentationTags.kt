package org.sempods.api.pod.resources

import jakarta.ws.rs.core.EntityTag
import org.eclipse.rdf4j.model.Model
import org.sempods.pods.ResourceValidator
import java.net.URI

/**
 * The strong entity tags of resource and slot representations, on both addressing routes
 * (`SPS-CRUD-002`).
 *
 * A tag hashes the statements a representation carries, the contexts a `?context=` selection named,
 * and a marker for its form. It changes when what the caller receives changes, and only then:
 *
 * - a write in a context the caller cannot read leaves the caller's tag unchanged;
 * - a caller who reads fewer contexts gets a different tag for a different body;
 * - one resource read with `?context=A` and without a selection gives two tags even when the
 *   statements coincide, because those are two representations (RFC 9110 §8.8.3).
 *
 * A write to context X is validated against the tags of X's selected representations
 * ([resourceWriteTarget], [slotWriteTarget]). A tag from a read that did not select exactly X does
 * not validate it.
 */
internal object RepresentationTags {

  /** The forms a resource is served in. */
  enum class Form(val marker: String) {
    JSON_LD("jsonld"),
    JSON_LD_WITH_CONTEXTS("contexts-jsonld"),
    N_QUADS("nquads"),
  }

  /** The tag of a resource representation of [statements], read with [selection] — null when the read named no context. */
  fun resource(statements: Model, selection: Set<URI>?, form: Form): EntityTag {
    val scope = selection?.map(URI::toString)?.sorted()?.joinToString(" ", prefix = "context ") ?: ""
    return EntityTag("${ResourceValidator.compute(statements, scope)}-${form.marker}")
  }

  /** The tag of the slot `(subject, predicate)` read in exactly [context]; [withContexts] for its named-graph form. */
  fun slot(statements: Model, subject: URI, predicate: URI, context: URI, withContexts: Boolean): EntityTag {
    val value = ResourceValidator.compute(statements, "slot $subject $predicate $context")
    return EntityTag(if (withContexts) "$value-contexts" else value)
  }

  /** Every current tag a write of the resource into [context] accepts, or none when it has no statements there. */
  fun resourceWriteTarget(statementsInContext: Model, context: URI): List<EntityTag> =
    if (statementsInContext.isEmpty()) emptyList()
    else withCompressed(Form.entries.map { resource(statementsInContext, setOf(context), it) })

  /** Every current tag a write of the slot in [context] accepts, or none when the slot is empty there. */
  fun slotWriteTarget(statementsInContext: Model, subject: URI, predicate: URI, context: URI): List<EntityTag> =
    if (statementsInContext.isEmpty()) emptyList()
    else withCompressed(listOf(false, true).map { slot(statementsInContext, subject, predicate, context, it) })

  /**
   * Jetty's `GzipHandler` serves a compressed read under `"<tag>--gzip"` and strips that suffix from
   * a conditional GET, but not from a PUT, PATCH or DELETE. A client sending it back sends a tag this
   * server issued for the same state, so a write accepts it.
   */
  private fun withCompressed(tags: List<EntityTag>): List<EntityTag> =
    tags + tags.map { EntityTag("${it.value}--gzip") }
}
