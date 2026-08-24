package org.sempods.query

import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.query.Dataset
import org.eclipse.rdf4j.query.impl.SimpleDataset
import java.net.URI

object SparqlSandbox {

  /**
   * `true` means the caller is authenticated/anonymous but has no readable contexts. RDF4J must
   * not receive this as an empty Dataset: an empty Dataset is treated like no restriction by the
   * store, so callers must short-circuit to an empty result first.
   */
  fun matchesNothing(restrictedContexts: Set<URI>?): Boolean = restrictedContexts?.isEmpty() == true

  fun buildDataset(restrictedContexts: Set<URI>?): Dataset? {
    restrictedContexts ?: return null
    require(restrictedContexts.isNotEmpty()) {
      "empty restrictedContexts must be short-circuited before building an RDF4J Dataset"
    }
    val vf = SimpleValueFactory.getInstance()
    val dataset = SimpleDataset()
    restrictedContexts.forEach { ctx ->
      val iri = vf.createIRI(ctx.toString())
      dataset.addDefaultGraph(iri)
      dataset.addNamedGraph(iri)
    }
    return dataset
  }
}
