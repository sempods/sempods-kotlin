package org.sempods.api.pod.resources

import com.google.inject.Inject
import org.sempods.commons.jaxrs.errors.ApiErrors
import org.sempods.SempodsFacade
import org.sempods.pods.grants.SempodsCredentials
import org.sempods.pods.PodFacade
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.impl.LinkedHashModel
import java.net.URI

/**
 * Shared read path for pod resources, used by [PodResourceEndpoint] (canonical LOD path,
 * `{pod}/{resourcePath}`) and [org.sempods.api.pod.system.resources.PodSystemResourcesEndpoint]
 * (b64-IRI whole-resource route, `{pod}/_system/resources/{b64(iri)}`).
 *
 * Centralizing context-visibility resolution and the ETag base value here is what guarantees
 * the two addressing routes produce a byte-identical representation and validator for the same
 * `(resource, contexts)` — the cross-route conditional-write parity required by
 * sempods-spec `spec/core/lod-crud.md` §5. A pod-owned IRI therefore has one identity whether
 * fetched via the pretty canonical path or the b64 system-layer route.
 */
class PodResourceReadService @Inject constructor(
  private val sempodsFacade: SempodsFacade,
  private val podContextWriteAuthorizer: PodContextWriteAuthorizer,
  private val podFacade: PodFacade,
) {

  /**
   * Apply read-side context downscoping per `SPS-CRUD-014` (sempods-spec): parse repeated `?context=`
   * parameters, intersect with the caller's readable contexts.
   *
   * - `null` return = no `?context=` parameter → caller-visible context set is the full readable
   *   set (anonymous public-only, authenticated explicit grants + optional `public-read`).
   * - empty set = `?context=` was passed but every requested context is unknown or unreadable →
   *   caller will see `404` via [loadVisibleResourceModelOrThrow].
   * - non-empty set = exact set of contexts the caller asked for AND can read.
   */
  fun resolveVisibleContexts(
    pod: String,
    credentials: SempodsCredentials,
    rawContexts: List<String>?,
  ): Set<URI>? {
    val downscope = podContextWriteAuthorizer.resolveReadDownscopeOrEmpty(pod, rawContexts)
      ?: return credentials.restrictedContexts
    val readable = credentials.restrictedContexts
    return if (readable == null) downscope.toSet() else downscope.intersect(readable)
  }

  /**
   * Load the visible representation of [resourceUri], filtered to [visibleContexts]. Throws 404
   * when the resource has no statements at all, or none in a context the caller may read — the
   * visibility 404 must be decided here, before any ETag/precondition handling, so a conditional
   * request cannot leak the existence (and content-hash fingerprint) of a resource the caller
   * cannot see.
   */
  fun loadVisibleResourceModelOrThrow(
    pod: String,
    resourceUri: URI,
    visibleContexts: Set<URI>?,
  ): Model {
    val model = podFacade.getResource(pod, resourceUri)
      ?: throw ApiErrors.throwNotFoundError()
    val visibleModel = filterByContexts(model, visibleContexts)
    if (visibleModel.isEmpty()) {
      throw ApiErrors.throwNotFoundError()
    }
    return visibleModel
  }

  /**
   * Strong-validator base value for a resource's representation, the single source of truth for
   * the ETag of both LOD routes. The validator is global across contexts (LOD identity is global);
   * [includeContexts] distinguishes the named-graph representation from the canonical one.
   *
   * Mirrors the original `PodResourceEndpoint.entityTag` guard: a missing validator on a
   * non-existent pod is a 404, not a `"0"` tag.
   */
  fun resourceTagBaseValue(pod: String, resourceUri: URI, includeContexts: Boolean): String {
    val validator = sempodsFacade.getResourceValidator(pod, resourceUri)
    if (validator == null && !sempodsFacade.existsPod(pod)) {
      throw ApiErrors.throwNotFoundError()
    }
    val representationSuffix = if (includeContexts) "-contexts" else ""
    return "${validator ?: "0"}$representationSuffix"
  }

  /**
   * Strong validator for write-precondition evaluation; `null` when the resource has no
   * statements yet (so `If-None-Match: *` create-or-fail can be honored).
   */
  fun resourceWriteValidator(pod: String, resourceUri: URI): String? =
    sempodsFacade.getResourceValidator(pod, resourceUri)

  private fun filterByContexts(model: Model, visibleContexts: Set<URI>?): Model {
    visibleContexts ?: return model
    val allowed = visibleContexts.map(URI::toString).toSet()
    val filtered = LinkedHashModel()
    model.forEach { stmt ->
      val contextUri = stmt.context?.stringValue()
      if (contextUri != null && allowed.contains(contextUri)) {
        filtered.add(stmt)
      }
    }
    return filtered
  }
}
