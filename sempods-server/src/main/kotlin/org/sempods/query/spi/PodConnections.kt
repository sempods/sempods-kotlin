package org.sempods.query.spi

import org.eclipse.rdf4j.repository.RepositoryConnection
import org.sempods.spec.PodRef

/**
 * Where [org.sempods.query.SparqlQueryService] gets a connection to a pod's RDF store. A
 * deployment that keeps a pod's statements in an RDF4J repository of its own binds this, and
 * `SparqlQueryService` and the retrieval engines built on it read from there.
 *
 * The connection exposes [pod]'s statements and no other pod's, in their contexts. That is the pod
 * boundary, and the service does not draw it: a caller may pass no `Dataset`, and its query then
 * reads everything the connection exposes. A `Dataset`, when there is one, narrows the read to
 * contexts within the pod, and the store applies it the way RDF4J's own stores do. The service runs
 * read queries only.
 */
interface PodConnections {

  /**
   * Run [block] with a connection to [pod]'s store, and close the connection when it returns.
   * [block] only reads. An implementation that holds no store for [pod] throws: this server's
   * answers `404`.
   */
  fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T
}
