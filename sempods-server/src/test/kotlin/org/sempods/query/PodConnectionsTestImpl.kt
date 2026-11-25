package org.sempods.query

import org.eclipse.rdf4j.repository.Repository
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.sempods.query.spi.PodConnections
import org.sempods.spec.PodRef

/**
 * The [PodConnections] one `withSetup { }` block's SPARQL reads reach: [real] until a test says
 * otherwise through [answerWith].
 */
class PodConnectionsTestImpl(val real: PodConnections) : PodConnections {

  @Volatile
  private var answer: PodConnections = real

  /** Serves every pod from [repository] from now on, as a deployment with a store of its own would. */
  fun answerWith(repository: Repository) {
    answer = object : PodConnections {
      override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T =
        repository.connection.use(block)
    }
  }

  override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T =
    answer.withConnection(pod, block)
}
