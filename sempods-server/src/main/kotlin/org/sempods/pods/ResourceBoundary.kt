package org.sempods.pods

import org.eclipse.rdf4j.model.BNode
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.Statement

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
   * statement must have [resourceIri] as subject, must not carry a blank node, and every IRI in it
   * — subject, predicate, object, a literal's datatype, the context — must pass [IriSyntax]. An
   * in-process write backstop, mirrored at the HTTP layer.
   *
   * It judges the whole document, statements already stored in other contexts included, so a
   * write to a resource that holds an IRI from before the [IriSyntax] rule fails until that IRI is
   * repaired.
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
      iris(st).forEach { iri ->
        IriSyntax.rejectionReason(iri.stringValue())?.let { reason ->
          throw IllegalArgumentException("Model for resource '$resourceIri' contains an IRI a pod may not store: $reason")
        }
      }
    }
  }

  private fun iris(st: Statement): List<IRI> = listOfNotNull(
    st.subject as? IRI,
    st.predicate,
    st.`object` as? IRI,
    (st.`object` as? Literal)?.datatype,
    st.context as? IRI,
  )
}
