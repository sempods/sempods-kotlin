package org.sempods.api.pod.system.contexts

import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.rio.RDFFormat
import org.sempods.pods.contexts.ContextPathRules
import org.sempods.pods.contexts.ContextUriResolution
import org.sempods.rdf.RdfWriterUtil
import org.sempods.rdf.stream.RdfSource
import org.sempods.rdf.stream.toStreamingOutput
import java.net.URI

/**
 * What every context registry answer shares, the reads in [PodContextsEndpoint] and the lifecycle in
 * [PodContextLifecycleRoutes]: the representation, the transitional envelope's headers, the context
 * IRI a path names, and the refusals.
 */
internal object ContextRegistryResponses {

  private const val DEPRECATION_ISSUE = "https://github.com/sempods/sempods-kotlin/issues/184"

  fun entity(format: RegistryFormat, model: Model, subject: IRI, legacy: () -> Any): Any =
    when (format) {
      RegistryFormat.JSON_LD -> RdfWriterUtil.toCanonicalJsonLdEntry(model, subject, RdfWriterUtil.CanonicalJsonLd.REGISTRY)
      RegistryFormat.N_QUADS -> RdfSource.of(model).toStreamingOutput(RDFFormat.NQUADS)
      RegistryFormat.LEGACY_JSON -> legacy()
    }

  /**
   * What the transitional envelope owes on its own. `Cache-Control` and `Vary` belong to the whole
   * route and are set by [ContextRegistryCacheFilter], which also reaches the answers no method
   * builds.
   */
  fun deprecationHeaders(builder: Response.ResponseBuilder, format: RegistryFormat): Response.ResponseBuilder {
    if (format == RegistryFormat.LEGACY_JSON) {
      builder.header("Deprecation", "true")
        .header(HttpHeaders.LINK, "<$DEPRECATION_ISSUE>; rel=\"deprecation\"")
    }
    return builder
  }

  /** Nothing the registry produces is acceptable. On `PUT` this is reached before anything is written. */
  fun notAcceptable(): Response = Response.status(Response.Status.NOT_ACCEPTABLE)
    .entity("the context registry answers application/ld+json, application/n-quads or application/json")
    .type(MediaType.TEXT_PLAIN)
    .build()

  /** Hidden and absent are one answer, down to the headers and the missing validator (`SPS-CTX-035`). */
  fun unknownContext(): WebApplicationException = WebApplicationException(
    Response.status(404).entity("unknown context").type(MediaType.TEXT_PLAIN).build()
  )

  fun badContextPath(message: String): WebApplicationException =
    WebApplicationException(Response.status(400).entity(message).type("text/plain").build())

  /**
   * Maps the request path onto the canonical context IRI. Since contexts moved into the reserved
   * area the two are the same string — `{pod}/_system/contexts/apps/notes/public` addresses
   * `{pod}/_system/contexts/apps/notes/public`, so there is no decomposition here and no
   * recomposition on the client side that could drift apart.
   *
   * Built by [ContextPathRules.resolve], shared with the consent dialog — the prefix is prepended
   * there, never taken from the caller, and a path that cannot be an addressable IRI (fragment,
   * query, broken syntax) is rejected. Applies to every verb: a string that is not addressable was
   * never a context, whenever it was written. What a caller may *name* is a separate rule, applied on
   * creation only ([ContextPathRules.rejectionReason]).
   */
  fun contextUri(podBaseUrl: String, contextPath: String): URI =
    when (val resolution = ContextPathRules.resolve(podBaseUrl, contextPath)) {
      is ContextUriResolution.Resolved -> resolution.uri
      is ContextUriResolution.Rejected -> throw badContextPath(resolution.reason)
    }
}
