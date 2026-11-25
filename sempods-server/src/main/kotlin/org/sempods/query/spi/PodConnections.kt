package org.sempods.query.spi

import org.eclipse.rdf4j.repository.RepositoryConnection
import org.sempods.spec.PodRef

/**
 * Where [org.sempods.query.SparqlQueryService] gets a connection to a pod's RDF store. A
 * deployment that keeps a pod's statements in an RDF4J repository of its own binds this, and
 * `SparqlQueryService` and the retrieval engines built on it read from there.
 *
 * The service runs read queries only, and it pins each one to the request's contexts with an RDF4J
 * `Dataset`. So the store behind the connection holds the pod's statements in their contexts, and
 * it applies a `Dataset` the way RDF4J's own stores do.
 */
interface PodConnections {

  /**
   * Run [block] with a connection to [pod]'s store, and close the connection when it returns.
   * [block] only reads. An implementation that holds no store for [pod] throws: this server's
   * answers `404`.
   */
  fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T
}
