package org.sempods.api.pod.system.meta

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.inject.Inject
import org.sempods.api.SempodsBaseEndpoint
import org.sempods.pods.PodFacade
import org.sempods.pods.mongo.persist.PodDao
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * Pod-level metadata under `{pod}/_system/meta/...`. Grouping these reads under one
 * reserved prefix keeps the `_system` namespace tidy as more pod-metadata endpoints land
 * (each can be a sibling method here with its own auth) and avoids flat-namespace sprawl.
 *
 * - `GET {pod}/_system/meta/date-modified` — the pod's `dateModified`.
 *
 * The field is named after the schema.org `dateModified` property (the same token the
 * events API exposes), not the HTTP `Last-Modified` header: the value *is* the pod's
 * `dateModified` (the stored [org.sempods.pods.mongo.persist.PodDao.fetchLastModifiedAt],
 * bumped on every write incl. deletes); the ETag is just one consumer. It backs a consumer's own
 * listing ETag, which `SempodsPodClient.lastModifiedAt` reads anonymously.
 *
 * `200 { "dateModified": "<iso8601>" }` for an existing pod, `404` when the pod does not
 * exist, `401` on an invalid bearer. The timestamp is pod-global (not context-scoped), so
 * anonymous and authenticated callers see the same value; authentication runs only to reject
 * invalid bearers and to record the service-client audit row consistent with the rest of the
 * `_system` surface.
 */
@Path("{pod}/_system/meta")
@Produces(MediaType.APPLICATION_JSON)
class PodMetaEndpoint @Inject constructor(
  podFacade: PodFacade,
  podDao: PodDao,
) : SempodsBaseEndpoint(
  podFacade = podFacade,
  podDao = podDao,
) {

  @GET
  @Path("date-modified")
  fun dateModified(
    @PathParam("pod") pod: String,
  ): Response {
    // 404 on unknown pod, 401 on invalid bearer; anonymous is accepted (the timestamp is not
    // context-sensitive). Also records the service-client audit row when authenticated.
    authenticate(pod)
    val dateModified = podDao.fetchLastModifiedAt(pod)
    return Response.ok(PodDateModifiedResponse(dateModified = dateModified?.toString())).build()
  }
}

/** Response for `GET {pod}/_system/meta/date-modified`. `dateModified` is null for a pod with no writes. */
data class PodDateModifiedResponse(
  @field:[JsonProperty("dateModified") JsonInclude(JsonInclude.Include.ALWAYS)]
  val dateModified: String?,
)
