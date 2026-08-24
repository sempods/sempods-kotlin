package org.sempods.pods

import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model

/**
 * Resource boundary invariant for the write-through store: a resource's model may only contain
 * statements **about the resource itself** (subject = the resource IRI), and pod data is
 * **fully IRI/literal-valued — blank nodes are forbidden**.
 *
 * Blank nodes have no global identity (their labels are document/parse-local), which makes them
 * unaddressable, unauthorizable (a context is the IRI-named authorization unit), and the source of
 * the hard RDF operational problems (isomorphism, relabeling, cross-context closures). sempods has
 * never produced them — the canonical JSON-LD CRUD API cannot express anonymous nested objects —
 * so they are rejected outright; a nested value must be its own IRI-named resource. External RDF
 * carrying blank nodes would be skolemized at the import boundary (a future concern).
 */
object ResourceBoundary {

  /**
   * Enforce the boundary before persisting [model] as the document of [resourceIri]: every
   * statement must have [resourceIri] as subject and must not carry a blank node. An in-process
   * write backstop, mirrored at the HTTP layer.
   */
  fun requireWithinBoundary(resourceIri: IRI, model: Model) {
    model.forEach { st ->
      require(st.subject == resourceIri) {
        "Model for resource '$resourceIri' contains a statement about a different subject " +
          "'${st.subject.stringValue()}' — a resource may only carry statements about itself"
      }
      require(st.subject !is BNode && st.`object` !is BNode) {
        "Model for resource '$resourceIri' contains a blank node — pod data must be fully " +
          "IRI/literal-valued; a nested value must be its own IRI-named resource"
      }
    }
  }
}
