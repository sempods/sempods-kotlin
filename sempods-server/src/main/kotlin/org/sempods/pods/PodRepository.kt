package org.sempods.pods

import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.repository.RepositoryConnection
import java.net.URI

/**
 * Per-pod RDF store abstraction. Operates at the resource level (not triple level).
 * Each pod gets its own [PodRepository] instance.
 *
 * Both writes and reads go against the in-memory store ([InMemoryPodRepository]); MongoDB holds the
 * per-context backup the store is rebuilt from, written as a side effect of each committed write.
 * See `write-through.md`.
 */
interface PodRepository {

  /** Read the full model for a resource, or null if not found. */
  fun getResource(uri: URI): Model?

  /** Read only the statements for a resource within a specific context. */
  fun getResource(uri: URI, context: URI): Model?

  /**
   * A strong ETag validator for the resource (a content hash over its own-subject statements), or
   * null if the resource does not exist. See [ResourceValidator].
   */
  fun fetchResourceValidator(uri: URI): String?

  /** Write a resource model. Returns true if the store was modified (an isomorphic write is a no-op). */
  fun putResource(uri: URI, model: Model): Boolean

  /** Remove all outgoing statements of a resource in a given context. Returns true if modified. */
  fun removeFromContext(uri: URI, context: URI): Boolean

  /** Remove an entire context from all resources in this pod. Returns true if modified. */
  fun removeContext(context: URI): Boolean

  /** Fully delete a resource. Returns true if modified. */
  fun deleteResource(uri: URI): Boolean

  /** Check whether a resource exists, optionally filtered by types and/or contexts. */
  fun existsResource(uri: URI, types: Collection<URI>? = null, contexts: Collection<URI>? = null): Boolean

  /** Find resources that reference [objectUri] within [context]. */
  fun findReferencingResources(context: URI, objectUri: URI): Set<URI>

  /** Execute a block with a [RepositoryConnection] to the underlying RDF store. */
  fun <T> withConnection(block: (RepositoryConnection) -> T): T
}
