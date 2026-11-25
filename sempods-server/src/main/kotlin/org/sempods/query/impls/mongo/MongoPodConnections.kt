package org.sempods.query.impls.mongo

import com.google.inject.Inject
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.sempods.commons.jaxrs.errors.ApiErrors
import org.sempods.pods.PodRepositoryCache
import org.sempods.query.spi.PodConnections
import org.sempods.spec.PodRef

/** [PodConnections] from [PodRepositoryCache], the in-memory stores this server loads from MongoDB. */
class MongoPodConnections @Inject internal constructor(
  private val podRepositoryCache: PodRepositoryCache,
) : PodConnections {

  override fun <T> withConnection(pod: PodRef, block: (RepositoryConnection) -> T): T {
    val repo = podRepositoryCache.get(pod.name)
      ?: throw ApiErrors.throwNotFoundError("pod", pod.name)
    return repo.withConnection(block)
  }
}
